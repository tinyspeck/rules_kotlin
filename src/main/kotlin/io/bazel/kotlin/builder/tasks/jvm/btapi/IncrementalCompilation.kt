/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.bazel.kotlin.builder.tasks.jvm.btapi

import io.bazel.kotlin.builder.tasks.BtapiRuntime
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.utils.fingerprintOf
import io.bazel.kotlin.builder.utils.jars.SourceDependenciesFile
import io.bazel.kotlin.builder.utils.jars.jdepsOf
import io.bazel.kotlin.compiler.CompilerPluginSpec
import io.bazel.kotlin.compiler.IncrementalCompilationSpec
import io.bazel.kotlin.compiler.KotlinBtapiCompiler
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Collectors
import kotlin.concurrent.withLock

/** Bumped whenever the layout or the meaning of the persisted state changes. */
private const val STATE_FORMAT = "1"

/** The exit code of a compilation that failed on errors in the sources. */
private const val COMPILATION_ERROR = 1

/** How long a classpath snapshot that no compilation used is kept. */
private val SNAPSHOT_RETENTION: Duration = Duration.ofDays(3)

/**
 * The jdeps plugin options that list the classpath. The state survives their changes: the
 * compiler detects classpath changes from the snapshots, and [IncrementalCompilation] writes the
 * jdeps from the dependencies of each source.
 */
private val JDEPS_CLASSPATH_OPTIONS = setOf("direct_dependencies", "full_classpath")

/** The strict deps levels that check the dependencies of the sources. */
private val STRICT_DEPS_CHECKED = setOf("warn", "error")

/**
 * Snapshot-based incremental compilation for the Build Tools API path.
 *
 * Bazel runs every compile action against empty output directories, while the Kotlin incremental
 * compiler compiles against the outputs and caches of the previous compilation. The worker keeps
 * that state per target under [root], outside of the action's directories, compiles into it, and
 * copies the results into the action's outputs. The state of a target is only reused while the
 * compilation's configuration (compiler flags and plugins, the friend paths, the requested
 * outputs) is unchanged and, under strict deps, no direct dependency was removed; any such change,
 * an interrupted compilation, or a failure the incremental compiler could not roll back starts
 * over with a full compilation.
 *
 * File contents are identified by the digests Bazel sends with each work request, computed with
 * the build's `--digest_function`, so incremental compilation needs the persistent worker
 * protocol. Source changes are computed from those digests against the sources of the last
 * successful compilation; classpath changes, including added and removed entries, are detected by
 * the compiler from per-entry snapshots, which are shared across targets and keyed by the entry's
 * digest.
 */
class IncrementalCompilation(
  private val root: Path,
) {
  private val targetLocks = ConcurrentHashMap<Path, TargetLock>()
  private val snapshotsPruned = AtomicBoolean(false)

  /** Marks [root] as used by this worker; its disappearance means the root does not persist. */
  private val instanceMarker: Path =
    root.resolve(INSTANCES_DIR).resolve(UUID.randomUUID().toString())
  private var instanceMarked = false
  private var rootResets = false

  private val snapshotsDir: Path get() = root.resolve("classpath-snapshots")

  /** A target's in-process lock, removed once no compilation holds or awaits it. */
  private class TargetLock {
    val lock = ReentrantLock()

    /** The compilations holding or awaiting [lock]; changed only within [targetLocks]' compute. */
    var users = 0
  }

  /** Where the compiler plugins of a compilation write to, see [compile]. */
  class PluginOutputs(
    /** The directory the ABI plugin writes the ABI classes to. */
    val abiClasses: String,
    /** The file the jdeps plugin records the dependencies of each source in. */
    val sourceDependencies: String,
  )

  /** What [compile] hands to the compiler invocation. */
  class Invocation(
    val sources: List<String>,
    val destination: String,
    val plugins: List<CompilerPluginSpec>,
    val incrementalCompilation: IncrementalCompilationSpec,
  )

  private class Spec(
    override val workingDirectory: String,
    override val sourceChangesKnown: Boolean,
    override val modifiedSources: List<String>,
    override val removedSources: List<String>,
    override val classpathSnapshots: List<String>,
    override val additionalOutputDirectories: List<String>,
  ) : IncrementalCompilationSpec

  /** The persisted record of the last successful compilation of a target. */
  private class PreviousCompilation(
    val fingerprint: String,
    /** stable source path -> content digest */
    val sources: Map<String, String>,
    val directDependencies: Set<String>,
  )

  /** The state directory of one target. */
  private class TargetState(
    val dir: Path,
  ) {
    val record: Path = dir.resolve("state")
    val inProgress: Path = dir.resolve("in-progress")
    val caches: Path = dir.resolve("caches")
    val classes: Path = dir.resolve("classes")
    val abiClasses: Path = dir.resolve("abi_classes")
    val abiRound: Path = dir.resolve("abi_round")

    /** The dependencies of each source, from which the jdeps are written. */
    val sourceDependencies: Path = dir.resolve("source_deps")
    val stagedSources: Path = dir.resolve("srcs")

    /** Snapshots of the classpath entries the request has no digest for, recomputed each time. */
    val classpathSnapshots: Path = dir.resolve("classpath-snapshots")

    /** Discards everything but the lock file. */
    fun reset() {
      Files.list(dir).use { entries ->
        entries.filter { it.fileName.toString() != LOCK_FILE }.forEach(::deleteRecursively)
      }
    }

    companion object {
      const val LOCK_FILE = ".lock"
    }
  }

  /**
   * Compiles the task incrementally, or returns null when the worker cannot keep incremental state
   * (see [unavailability]) and the caller should compile as usual.
   *
   * @param classpath the compile classpath.
   * @param plugins the compiler plugins, given where they write to.
   * @param invoke runs the compiler and returns its output lines; throws
   *   [CompilationStatusException] when the compilation fails.
   */
  fun compile(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
    runtime: BtapiRuntime,
    compiler: KotlinBtapiCompiler,
    classpath: List<String>,
    plugins: (PluginOutputs) -> List<CompilerPluginSpec>,
    invoke: (Invocation) -> List<String>,
  ): List<String>? {
    val state =
      try {
        unavailability(context)?.let { reason ->
          context.whenTracing { printLines("incremental compilation unavailable", listOf(reason)) }
          return null
        }
        TargetState(Files.createDirectories(root.resolve(targetKey(task))))
      } catch (e: IOException) {
        context.whenTracing {
          printLines("incremental compilation unavailable", listOf(e.toString()))
        }
        return null
      }
    return withTargetLock(state) {
      compileWithState(context, task, runtime, compiler, classpath, plugins, invoke, state)
    }
  }

  /** Why the worker cannot compile incrementally, or null if it can. */
  private fun unavailability(context: CompilationTaskContext): String? =
    when {
      context.inputDigests.isEmpty() -> {
        "the request has no input digests: the action does not run in a persistent worker"
      }

      !rootPersists(context) -> {
        "$root is reset between requests, e.g. by a sandboxed worker"
      }

      else -> {
        null
      }
    }

  /**
   * Whether [root] keeps its contents between requests. Bazel resets the directory a sandboxed
   * worker runs in before each request, which discards the state: the first compilation marks the
   * root for this worker, and once the marker disappears, the worker compiles non-incrementally.
   */
  @Synchronized
  private fun rootPersists(context: CompilationTaskContext): Boolean {
    if (rootResets) {
      return false
    }
    if (!instanceMarked) {
      Files.createDirectories(instanceMarker.parent)
      Files.write(instanceMarker, ByteArray(0))
      instanceMarker.toFile().deleteOnExit()
      instanceMarked = true
    } else if (!Files.exists(instanceMarker)) {
      rootResets = true
      context.printLines(
        "warning: incremental compilation disabled",
        listOf("$root is reset between requests, e.g. by a sandboxed worker"),
      )
    }
    return !rootResets
  }

  private fun compileWithState(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
    runtime: BtapiRuntime,
    compiler: KotlinBtapiCompiler,
    classpath: List<String>,
    plugins: (PluginOutputs) -> List<CompilerPluginSpec>,
    invoke: (Invocation) -> List<String>,
    state: TargetState,
  ): List<String> {
    val digests = context.inputDigests
    val requestRoot =
      Paths
        .get(task.directories.temp)
        .toAbsolutePath()
        .normalize()
        .parent
    val runtimeKey =
      runtime.classpathFingerprint.ifEmpty { fingerprintOf(runtime.apiImplClasspath, digests) }
    val pluginSpecs =
      plugins(PluginOutputs(state.abiRound.toString(), state.sourceDependencies.toString()))
    val fingerprint = fingerprint(task, runtimeKey, pluginSpecs, requestRoot, digests)
    val directDependencies = task.inputs.directDependenciesList.toSet()

    val interrupted = Files.exists(state.inProgress)
    var previous = if (interrupted) null else readPreviousCompilation(state, task)
    // Strict deps are checked on the recompiled sources only, so a dependency that is no longer
    // direct must be checked against every source.
    val removedDirectDependency =
      task.info.strictKotlinDeps in STRICT_DEPS_CHECKED &&
        previous?.directDependencies?.all(directDependencies::contains) == false
    if (previous?.fingerprint != fingerprint || removedDirectDependency) {
      context.whenTracing {
        printLines(
          "incremental compilation",
          listOf(
            when {
              interrupted -> "the previous compilation did not complete"
              previous == null -> "no previous compilation state"
              removedDirectDependency -> "a direct dependency was removed"
              else -> "the compilation configuration changed"
            } + ", compiling from scratch",
          ),
        )
      }
      state.reset()
      previous = null
    }

    val sources = stageSources(task, requestRoot, state, digests)
    val modified = sources.filter { (path, digest) -> previous?.sources?.get(path) != digest }.keys
    val removed = previous?.sources?.keys?.filterNot(sources::containsKey) ?: emptyList()
    deleteRecursively(state.classpathSnapshots)
    val snapshots =
      classpath.withIndex().mapNotNull { (index, entry) ->
        snapshotOf(entry, index, state, runtimeKey, compiler, digests)
      }

    context.whenTracing {
      printLines(
        "incremental compilation",
        listOf("state: ${state.dir}") +
          if (previous == null) {
            listOf("source changes unknown")
          } else {
            modified.map { "modified: $it" } + removed.map { "removed: $it" }
          },
      )
    }

    Files.createDirectories(state.caches)
    Files.createDirectories(state.classes)
    Files.createDirectories(state.abiClasses)
    deleteRecursively(state.abiRound)
    Files.createDirectories(state.abiRound)
    prepareSourceDependencies(task, state, sources.keys, previous != null)
    Files.write(state.inProgress, ByteArray(0))

    val classesBefore = stamps(state.classes)
    val output =
      try {
        invoke(
          Invocation(
            sources = sources.keys.toList(),
            destination = state.classes.toString(),
            plugins = pluginSpecs,
            incrementalCompilation =
              Spec(
                workingDirectory = state.caches.toString(),
                sourceChangesKnown = previous != null,
                modifiedSources = modified.toList(),
                removedSources = removed,
                classpathSnapshots = snapshots,
                additionalOutputDirectories =
                  listOf(state.abiClasses.toString(), state.abiRound.toString()),
              ),
          ),
        )
      } catch (e: CompilationStatusException) {
        // The compiler restores its previous outputs and leaves its caches untouched when a
        // compilation fails on errors in the sources. Keep the state only if it did: otherwise
        // (e.g. a failed fallback to a full compilation, an internal error, or running out of
        // memory) the next compilation starts over.
        if (e.status == COMPILATION_ERROR && stamps(state.classes) == classesBefore) {
          Files.delete(state.inProgress)
        } else {
          state.reset()
        }
        throw e
      }

    val classesAfter = stamps(state.classes)
    copyTree(state.classes, Paths.get(task.directories.classes))
    if (task.outputs.abijar.isNotEmpty()) {
      mergeAbiClasses(state, classesBefore, classesAfter)
      copyTree(state.abiClasses, Paths.get(task.directories.abiClasses))
    }
    if (task.outputs.jdeps.isNotEmpty()) {
      writeJdeps(task, state)
    }
    writePreviousCompilation(
      state,
      PreviousCompilation(fingerprint, sources, directDependencies),
    )
    Files.delete(state.inProgress)
    return output
  }

  private fun <T> withTargetLock(
    state: TargetState,
    block: () -> T,
  ): T {
    val targetLock =
      targetLocks.compute(state.dir) { _, existing ->
        (existing ?: TargetLock()).apply { users++ }
      }!!
    try {
      return targetLock.lock.withLock {
        // The file lock serializes worker processes sharing the state root, e.g. the singleplex
        // worker instances of one Bazel server.
        FileChannel
          .open(
            state.dir.resolve(TargetState.LOCK_FILE),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
          ).use { channel -> channel.lock().use { block() } }
      }
    } finally {
      targetLocks.compute(state.dir) { _, existing ->
        existing?.takeIf { --it.users > 0 }
      }
    }
  }

  /**
   * The state directory name of the task's target. The label alone is ambiguous: a target can be
   * built in several configurations, which differ in their output paths.
   */
  private fun targetKey(task: JvmCompilationTask): String {
    val readable =
      task.info.label
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .takeLast(80)
    return "$readable-" + sha256("${task.info.label}\n${task.outputs.jar}").take(16)
  }

  /**
   * Maps the sources to the paths the compiler reads them from. Sources extracted or generated into
   * the action's own directories (source jar contents, generated sources) live under a different
   * directory in every action, so they are staged under the target's state directory: the
   * incremental compiler tracks sources by path. Returns the content digest of each source by its
   * compiled path, in compilation order: Bazel's digest of an input, or, for the files Bazel never
   * saw, a SHA-256 of the content.
   */
  private fun stageSources(
    task: JvmCompilationTask,
    requestRoot: Path,
    state: TargetState,
    inputDigests: Map<String, String>,
  ): Map<String, String> {
    val sources = LinkedHashMap<String, String>()
    val staged = mutableSetOf<Path>()
    for (source in task.inputs.javaSourcesList + task.inputs.kotlinSourcesList) {
      val path = Paths.get(source).toAbsolutePath().normalize()
      if (path.startsWith(requestRoot)) {
        val target = state.stagedSources.resolve(requestRoot.relativize(path).toString())
        val content = Files.readAllBytes(path)
        if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(content)) {
          Files.createDirectories(target.parent)
          Files.write(target, content)
        }
        staged.add(target)
        sources[target.toString()] = sha256(content)
      } else {
        sources[path.toString()] =
          inputDigests.digestOf(source) ?: sha256(Files.readAllBytes(path))
      }
    }
    if (Files.isDirectory(state.stagedSources)) {
      Files.walk(state.stagedSources).use { paths ->
        paths
          .filter { Files.isRegularFile(it) && it !in staged }
          .collect(Collectors.toList())
          .forEach(Files::delete)
      }
    }
    return sources
  }

  /**
   * The jdeps plugin replaces the dependencies of the sources it analyzes in the target's
   * [TargetState.sourceDependencies] and keeps the others'. Drops the dependencies of the sources
   * the target no longer has, or starts over without a previous compilation.
   */
  private fun prepareSourceDependencies(
    task: JvmCompilationTask,
    state: TargetState,
    sources: Set<String>,
    hasPrevious: Boolean,
  ) {
    if (task.outputs.jdeps.isEmpty()) {
      return
    }
    val dependencies =
      if (hasPrevious) {
        // The compiler reports a source by the path it was given, which may be a link.
        val current = sources.map(::canonicalPath).toSet()
        SourceDependenciesFile.read(state.sourceDependencies).filterKeys {
          it.isEmpty() || canonicalPath(it) in current
        }
      } else {
        emptyMap()
      }
    SourceDependenciesFile.write(state.sourceDependencies, dependencies)
  }

  /**
   * Writes the jdeps of every source of the target: the plugin does not run when the compiler
   * recompiles nothing, and analyzes only the recompiled sources when it does. They are the jdeps
   * a full compilation writes.
   */
  private fun writeJdeps(
    task: JvmCompilationTask,
    state: TargetState,
  ) {
    val dependencies = SourceDependenciesFile.read(state.sourceDependencies).values
    val jdeps =
      jdepsOf(
        task.info.label,
        task.inputs.directDependenciesList,
        dependencies.flatMapTo(LinkedHashSet()) { it.explicitJars },
        dependencies.flatMapTo(LinkedHashSet()) { it.implicitJars },
      )
    Files.newOutputStream(Paths.get(task.outputs.jdeps)).use(jdeps::writeTo)
  }

  /**
   * The ABI plugin writes the ABI of the classes compiled in this round only. Replaces the ABI of
   * every class the compilation wrote or deleted with this round's output: a class the plugin did
   * not emit again was removed from the ABI (or from the sources).
   */
  private fun mergeAbiClasses(
    state: TargetState,
    before: Map<String, FileStamp>,
    after: Map<String, FileStamp>,
  ) {
    (before.keys + after.keys)
      .filter { before[it] != after[it] }
      .forEach { Files.deleteIfExists(state.abiClasses.resolve(it)) }
    copyTree(state.abiRound, state.abiClasses)
  }

  /**
   * The snapshot of one classpath entry, computed once per entry digest and shared across targets.
   * An entry the request has no digest for, e.g. a directory, is snapshotted for this compilation
   * only. Returns null for entries that do not exist, which the compiler ignores as well.
   */
  private fun snapshotOf(
    entry: String,
    index: Int,
    state: TargetState,
    runtimeKey: String,
    compiler: KotlinBtapiCompiler,
    inputDigests: Map<String, String>,
  ): String? {
    val path = Paths.get(entry)
    if (!Files.exists(path)) {
      return null
    }
    // External repositories rarely change; the class-level granularity keeps their snapshots small.
    val classLevel = entry.startsWith("external/") || "/external/" in entry
    val digest = inputDigests.digestOf(entry)
    if (digest == null) {
      Files.createDirectories(state.classpathSnapshots)
      val snapshot = state.classpathSnapshots.resolve("$index.bin")
      compiler.snapshotClasspathEntry(
        path.toAbsolutePath().toString(),
        snapshot.toString(),
        classLevel,
      )
      return snapshot.toString()
    }
    val name = sha256("$STATE_FORMAT\n$runtimeKey\n$digest\n$classLevel")
    val snapshot = snapshotsDir.resolve("$name.bin")
    if (Files.exists(snapshot)) {
      Files.setLastModifiedTime(snapshot, FileTime.from(Instant.now()))
      return snapshot.toString()
    }
    Files.createDirectories(snapshotsDir)
    pruneSnapshotsOnce()
    val temporary = Files.createTempFile(snapshotsDir, name, ".tmp")
    try {
      compiler.snapshotClasspathEntry(
        path.toAbsolutePath().toString(),
        temporary.toString(),
        classLevel,
      )
      Files.move(temporary, snapshot, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: FileAlreadyExistsException) {
      // Computed concurrently by another compilation.
    } finally {
      Files.deleteIfExists(temporary)
    }
    return snapshot.toString()
  }

  private fun pruneSnapshotsOnce() {
    if (!snapshotsPruned.compareAndSet(false, true)) {
      return
    }
    val cutoff = Instant.now().minus(SNAPSHOT_RETENTION)
    Files.list(snapshotsDir).use { snapshots ->
      snapshots
        .filter { Files.getLastModifiedTime(it).toInstant().isBefore(cutoff) }
        .collect(Collectors.toList())
        .forEach { runCatching { Files.deleteIfExists(it) } }
    }
  }

  /**
   * Everything a compilation's outputs depend on besides the sources and the classpath, with the
   * action-specific directory replaced by a placeholder.
   */
  private fun fingerprint(
    task: JvmCompilationTask,
    runtimeKey: String,
    plugins: List<CompilerPluginSpec>,
    requestRoot: Path,
    inputDigests: Map<String, String>,
  ): String {
    val lines = mutableListOf<String>()

    fun add(
      key: String,
      value: Any,
    ) {
      lines.add("$key=${value.toString().replace(requestRoot.toString(), "<action>")}")
    }
    add("format", STATE_FORMAT)
    add("runtime", runtimeKey)
    // The compiler resolves the JDK classes against the worker's JVM, which no snapshot covers.
    add("jdk", System.getProperty("java.home"))
    add("jdk_version", System.getProperty("java.runtime.version"))
    with(task.info) {
      add("module", moduleName)
      add("jvm_target", toolchainInfo.jvm.jvmTarget)
      add("api_version", toolchainInfo.common.apiVersion)
      add("language_version", toolchainInfo.common.languageVersion)
      passthroughFlagsList.forEach { add("flag", it) }
      friendPathsList.forEach { add("friend", it) }
      add("strict_deps", strictKotlinDeps)
      add("reduced_classpath", reducedClasspathMode)
    }
    plugins.forEach { plugin ->
      add("plugin", plugin.id)
      plugin.classpath.forEach {
        // A plugin jar the request has no digest for cannot be tracked: never reuse the state.
        add("plugin_classpath", "$it ${inputDigests.digestOf(it) ?: UUID.randomUUID()}")
      }
      plugin.options
        .filterNot {
          plugin.id == JDEPS_GEN_PLUGIN_ID && it.substringBefore('=') in JDEPS_CLASSPATH_OPTIONS
        }.forEach { add("plugin_option", it) }
    }
    add("jdeps", task.outputs.jdeps.isNotEmpty())
    add("abi", task.outputs.abijar.isNotEmpty())
    return sha256(lines.joinToString("\n"))
  }

  private fun readPreviousCompilation(
    state: TargetState,
    task: JvmCompilationTask,
  ): PreviousCompilation? {
    val complete =
      Files.isRegularFile(state.record) &&
        Files.isDirectory(state.caches) &&
        Files.isDirectory(state.classes) &&
        (task.outputs.abijar.isEmpty() || Files.isDirectory(state.abiClasses)) &&
        (task.outputs.jdeps.isEmpty() || Files.isRegularFile(state.sourceDependencies))
    if (!complete) {
      return null
    }
    val lines = Files.readAllLines(state.record, StandardCharsets.UTF_8)
    if (lines.firstOrNull() != "format $STATE_FORMAT") {
      return null
    }
    var fingerprint: String? = null
    val sources = LinkedHashMap<String, String>()
    val directDependencies = mutableSetOf<String>()
    for (line in lines.drop(1)) {
      val fields = line.split(" ", limit = 3)
      when (fields[0]) {
        "fingerprint" -> fingerprint = fields[1]
        "source" -> sources[fields[2]] = fields[1]
        "direct_dependency" -> directDependencies.add(line.substringAfter(' '))
        else -> return null
      }
    }
    return fingerprint?.let { PreviousCompilation(it, sources, directDependencies) }
  }

  private fun writePreviousCompilation(
    state: TargetState,
    compilation: PreviousCompilation,
  ) {
    val lines =
      listOf("format $STATE_FORMAT", "fingerprint ${compilation.fingerprint}") +
        compilation.sources.map { (path, digest) -> "source $digest $path" } +
        compilation.directDependencies.map { "direct_dependency $it" }
    val temporary = Files.createTempFile(state.dir, "state", ".tmp")
    Files.write(temporary, lines, StandardCharsets.UTF_8)
    Files.move(
      temporary,
      state.record,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )
  }

  private companion object {
    /** Holds the markers of the workers using the root, see [rootPersists]. */
    const val INSTANCES_DIR = "workers"
  }
}

/**
 * Bazel's digest of an input, as the request carries it: computed with the build's
 * `--digest_function`. Null when the request has none for the path.
 */
private fun Map<String, String>.digestOf(path: String): String? =
  get(path)?.takeIf { it.isNotEmpty() }

private fun canonicalPath(path: String): String =
  try {
    Paths.get(path).toRealPath().toString()
  } catch (_: IOException) {
    Paths
      .get(path)
      .toAbsolutePath()
      .normalize()
      .toString()
  }

/** The size and modification time of every file under [dir], by relative path. */
private fun stamps(dir: Path): Map<String, FileStamp> {
  if (!Files.isDirectory(dir)) {
    return emptyMap()
  }
  return Files.walk(dir).use { paths ->
    paths
      .filter { Files.isRegularFile(it) }
      .collect(Collectors.toList())
      .associate { file ->
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        dir.relativize(file).toString() to
          FileStamp(attributes.size(), attributes.lastModifiedTime())
      }
  }
}

private data class FileStamp(
  val size: Long,
  val modified: FileTime,
)

private fun copyTree(
  from: Path,
  to: Path,
) {
  if (!Files.isDirectory(from)) {
    return
  }
  Files.walk(from).use { paths ->
    paths.forEach { source ->
      val target = to.resolve(from.relativize(source).toString())
      if (Files.isDirectory(source)) {
        Files.createDirectories(target)
      } else {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }
}

private fun deleteRecursively(path: Path) {
  if (!Files.exists(path)) {
    return
  }
  Files.walk(path).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }
}

private fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))

private fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
