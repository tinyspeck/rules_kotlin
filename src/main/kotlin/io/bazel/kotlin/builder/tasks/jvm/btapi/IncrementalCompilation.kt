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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bumped whenever the layout or the meaning of the persisted state changes. */
private const val STATE_FORMAT = "1"

/** How long a classpath snapshot that no compilation used is kept. */
private val SNAPSHOT_RETENTION: Duration = Duration.ofDays(3)

/**
 * Snapshot-based incremental compilation for the Build Tools API path.
 *
 * Bazel runs every compile action against empty output directories, while the Kotlin incremental
 * compiler compiles against the outputs and caches of the previous compilation. The worker keeps
 * that state per target under [root], outside of the action's directories, compiles into it, and
 * copies the results into the action's outputs. The state of a target is only reused while the
 * compilation's configuration (compiler flags and plugins, the classpath entries, the direct
 * dependencies, the requested outputs) is unchanged; any change, an interrupted compilation, or a
 * failure the incremental compiler could not roll back starts over with a full compilation.
 *
 * Source changes are computed from content digests against the sources of the last successful
 * compilation; classpath changes are detected by the compiler from per-entry snapshots, which are
 * shared across targets and keyed by the entry's content.
 */
class IncrementalCompilation(
  private val root: Path,
) {
  private val targetLocks = ConcurrentHashMap<Path, ReentrantLock>()
  private val computedDigests = ConcurrentHashMap<Path, ComputedDigest>()
  private val snapshotsPruned = AtomicBoolean(false)

  private val snapshotsDir: Path get() = root.resolve("classpath-snapshots")

  private data class ComputedDigest(
    val size: Long,
    val modified: FileTime,
    val digest: String,
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
    val jdeps: Path = dir.resolve("jdeps")
    val stagedSources: Path = dir.resolve("srcs")

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
   * Compiles the task incrementally, or returns null when the state directory of the target cannot
   * be created (e.g. the worker runs inside a sandbox) and the caller should compile as usual.
   *
   * @param classpath the compile classpath.
   * @param plugins the compiler plugins, given the directory the ABI plugin writes to.
   * @param invoke runs the compiler and returns its output lines; throws
   *   [CompilationStatusException] when the compilation fails.
   */
  fun compile(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
    runtime: BtapiRuntime,
    compiler: KotlinBtapiCompiler,
    classpath: List<String>,
    plugins: (abiOutputDir: String) -> List<CompilerPluginSpec>,
    invoke: (Invocation) -> List<String>,
  ): List<String>? {
    val state =
      try {
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

  private fun compileWithState(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
    runtime: BtapiRuntime,
    compiler: KotlinBtapiCompiler,
    classpath: List<String>,
    plugins: (abiOutputDir: String) -> List<CompilerPluginSpec>,
    invoke: (Invocation) -> List<String>,
    state: TargetState,
  ): List<String> {
    val requestRoot = Paths.get(task.directories.temp).toAbsolutePath().normalize().parent
    val runtimeKey = runtimeKey(runtime, context.inputDigests)
    val pluginSpecs = plugins(state.abiRound.toString())
    val fingerprint =
      fingerprint(task, runtimeKey, classpath, pluginSpecs, requestRoot, context.inputDigests)

    var previous = if (Files.exists(state.inProgress)) null else readPreviousCompilation(state, task)
    if (previous?.fingerprint != fingerprint) {
      context.whenTracing {
        printLines(
          "incremental compilation",
          listOf(
            when {
              Files.exists(state.inProgress) -> "the previous compilation did not complete"
              previous == null -> "no previous compilation state"
              else -> "the compilation configuration changed"
            } + ", compiling from scratch",
          ),
        )
      }
      state.reset()
      previous = null
    }

    val sources = stageSources(task, requestRoot, state, context.inputDigests)
    val modified = sources.filter { (path, digest) -> previous?.sources?.get(path) != digest }.keys
    val removed = previous?.sources?.keys?.filterNot(sources::containsKey) ?: emptyList()
    val snapshots = classpath.mapNotNull { snapshotOf(it, runtimeKey, compiler, context.inputDigests) }

    context.whenTracing {
      printLines(
        "incremental compilation",
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
    seedJdeps(task, state, previous != null)
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
        // compilation fails. Keep the state only if it did: otherwise (e.g. a failed fallback to a
        // full compilation) the next compilation starts over.
        if (stamps(state.classes) == classesBefore) {
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
      Files.copy(
        Paths.get(task.outputs.jdeps),
        state.jdeps,
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
    writePreviousCompilation(state, PreviousCompilation(fingerprint, sources))
    Files.delete(state.inProgress)
    return output
  }

  private fun <T> withTargetLock(
    state: TargetState,
    block: () -> T,
  ): T =
    targetLocks.computeIfAbsent(state.dir) { ReentrantLock() }.withLock {
      // The file lock serializes worker processes sharing the state root, e.g. the singleplex
      // worker instances of one Bazel server.
      FileChannel
        .open(
          state.dir.resolve(TargetState.LOCK_FILE),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
        ).use { channel -> channel.lock().use { block() } }
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
   * compiled path, in compilation order.
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
          inputDigests[source]?.takeIf { it.isNotEmpty() } ?: sha256(Files.readAllBytes(path))
      }
    }
    if (Files.isDirectory(state.stagedSources)) {
      Files.walk(state.stagedSources).use { paths ->
        paths.filter { Files.isRegularFile(it) && it !in staged }.toList().forEach(Files::delete)
      }
    }
    return sources
  }

  /**
   * The jdeps plugin analyzes only the recompiled sources and merges the dependencies recorded in
   * its output file, so the output starts out with the dependencies of the previous compilation.
   */
  private fun seedJdeps(
    task: JvmCompilationTask,
    state: TargetState,
    hasPrevious: Boolean,
  ) {
    if (task.outputs.jdeps.isEmpty()) {
      return
    }
    val output = Paths.get(task.outputs.jdeps)
    if (hasPrevious) {
      Files.copy(state.jdeps, output, StandardCopyOption.REPLACE_EXISTING)
    } else {
      Files.deleteIfExists(output)
    }
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
   * The snapshot of one classpath entry, computed once per entry content. Returns null for entries
   * that do not exist, which the compiler ignores as well.
   */
  private fun snapshotOf(
    entry: String,
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
    val name = sha256("$STATE_FORMAT\n$runtimeKey\n${digestOf(entry, inputDigests)}\n$classLevel")
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
        .toList()
        .forEach { runCatching { Files.deleteIfExists(it) } }
    }
  }

  /** Identifies the compiler runtime, whose snapshot and cache formats the state depends on. */
  private fun runtimeKey(
    runtime: BtapiRuntime,
    inputDigests: Map<String, String>,
  ): String =
    runtime.classpathFingerprint.ifEmpty {
      sha256(runtime.apiImplClasspath.joinToString("\n") { "$it ${digestOf(it, inputDigests)}" })
    }

  /**
   * Everything a compilation's outputs depend on besides the sources and the classpath contents,
   * with the action-specific directory replaced by a placeholder.
   */
  private fun fingerprint(
    task: JvmCompilationTask,
    runtimeKey: String,
    classpath: List<String>,
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
      plugin.classpath.forEach { add("plugin_classpath", "$it ${digestOf(it, inputDigests)}") }
      plugin.options.forEach { add("plugin_option", it) }
    }
    classpath.forEach { add("classpath", it) }
    task.inputs.directDependenciesList.forEach { add("direct_dependency", it) }
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
        (task.outputs.jdeps.isEmpty() || Files.isRegularFile(state.jdeps))
    if (!complete) {
      return null
    }
    val lines = Files.readAllLines(state.record, StandardCharsets.UTF_8)
    if (lines.firstOrNull() != "format $STATE_FORMAT") {
      return null
    }
    var fingerprint: String? = null
    val sources = LinkedHashMap<String, String>()
    for (line in lines.drop(1)) {
      val fields = line.split(" ", limit = 3)
      when (fields[0]) {
        "fingerprint" -> fingerprint = fields[1]
        "source" -> sources[fields[2]] = fields[1]
        else -> return null
      }
    }
    return fingerprint?.let { PreviousCompilation(it, sources) }
  }

  private fun writePreviousCompilation(
    state: TargetState,
    compilation: PreviousCompilation,
  ) {
    val lines =
      listOf("format $STATE_FORMAT", "fingerprint ${compilation.fingerprint}") +
        compilation.sources.map { (path, digest) -> "source $digest $path" }
    val temporary = Files.createTempFile(state.dir, "state", ".tmp")
    Files.write(temporary, lines, StandardCharsets.UTF_8)
    Files.move(
      temporary,
      state.record,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )
  }

  /**
   * The content digest of a file: the digest Bazel sent with the request, or one computed from the
   * file and reused while the file's size and modification time are unchanged.
   */
  private fun digestOf(
    file: String,
    inputDigests: Map<String, String>,
  ): String {
    inputDigests[file]?.takeIf { it.isNotEmpty() }?.let { return it }
    val path = Paths.get(file).toAbsolutePath()
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
    if (attributes.isDirectory) {
      return sha256(
        stamps(path).toSortedMap().entries.joinToString("\n") { (relative, _) ->
          "$relative ${digestOf(path.resolve(relative).toString(), emptyMap())}"
        },
      )
    }
    computedDigests[path]?.let {
      if (it.size == attributes.size() && it.modified == attributes.lastModifiedTime()) {
        return it.digest
      }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().toHex().also {
      computedDigests[path] = ComputedDigest(attributes.size(), attributes.lastModifiedTime(), it)
    }
  }
}

/** The size and modification time of every file under [dir], by relative path. */
private fun stamps(dir: Path): Map<String, FileStamp> {
  if (!Files.isDirectory(dir)) {
    return emptyMap()
  }
  return Files.walk(dir).use { paths ->
    paths
      .filter { Files.isRegularFile(it) }
      .toList()
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

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
