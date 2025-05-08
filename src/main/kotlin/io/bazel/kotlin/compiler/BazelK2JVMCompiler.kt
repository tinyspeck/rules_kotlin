/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.compiler

import org.jetbrains.kotlin.analysis.utils.relfection.renderAsDataClassToString
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilationService
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.ProjectId
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.jvm.ClasspathSnapshotBasedIncrementalCompilationApproachParameters
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.incremental.ChangedFiles
import org.jetbrains.kotlin.incremental.createDirectory
import org.jetbrains.kotlin.ir.util.toIrConst
import org.jetbrains.kotlin.js.parser.parse
import org.jetbrains.kotlin.incremental.extractKotlinSourcesFromFreeCompilerArguments
import org.jetbrains.kotlin.incremental.storage.RelocatableFileToPathConverter
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

fun extractSnapshot(arg: String, args: MutableList<String>): String? {
  val index = args.indexOf(arg)
  return if (index != -1 && index + 1 < args.size) {
    val value = args[index + 1]
    args.removeAt(index + 1)
    args.removeAt(index)
    value
  } else null
}

class BasicKotlinLogger(override val isDebugEnabled: Boolean = true, file: String = "/Users/ekerber/kotlin.log") :
  KotlinLogger {
  val file = File(file).apply {
    parentFile?.createDirectory()
  }
  val b = "\n"
  override fun warn(msg: String) {
    file.appendText (msg + b)
  }

  override fun warn(msg: String, throwable: Throwable?) {
    file.appendText (msg + b)
  }

  override fun info(msg: String) {
    file.appendText (msg + b)
  }

  override fun lifecycle(msg: String) {
    file.appendText (msg + b)
  }

  override fun debug(msg: String) {
    file.appendText (msg + b)
  }

  override fun error(msg: String, throwable: Throwable?) {
    file.outputStream().bufferedWriter().use { writer ->
      file.appendText (msg + b + "\n")
      throwable?.let { file.appendText(it.stackTraceToString() + "\n") }
    }
  }
}

/**/
@Suppress("unused")
class BazelK2JVMCompiler {
  @OptIn(ExperimentalBuildToolsApi::class)
  fun exec(
    errStream: java.io.PrintStream,
    vararg args: String,
  ): ExitCode {
    System.setProperty("idea.home.path", "/tmp/kotlin-api")
    System.setProperty("idea.io.use.fallback", "true")
    System.setProperty("zip.handler.uses.crc.instead.of.timestamp", "true")

    var mutableArgs = args.toMutableList()
    val snapshots = extractSnapshot("-snapshot", mutableArgs)
      ?.split(":")
      ?.map { File(it) }
      .orEmpty()

    val label = extractSnapshot("-label", mutableArgs)!!

    val packagePath = mutableArgs.indexOf("-module-name").let {
      args[it + 1].split("-").first().replace("_", "/")
    }

    val moduleName = mutableArgs.indexOf("-module-name").let {
      args[it + 1]
    }

    val isKsp = mutableArgs.indexOf("-XPlugin='voodoo'")
    val type = if (isKsp != -1) "ksp" else "kotlin"

    val projectId = ProjectId.ProjectUUID(UUID.randomUUID())
    val service = CompilationService.loadImplementation(this.javaClass.classLoader!!)

    val executionConfig = service.makeCompilerExecutionStrategyConfiguration()

    val incrementalDir = Paths.get("").toAbsolutePath().parent.resolve("_kotlin_incremental/$packagePath/$moduleName/$type/$label")

    val compilationConfig = service.makeJvmCompilationConfiguration().apply {

      useIncrementalCompilation(
        workingDirectory = incrementalDir.toFile(),
        // For Bazel, this will always be ToBeCalculated. We don't have a way to get the sources changes from the last build.
        sourcesChanges = SourcesChanges.ToBeCalculated,
        approachParameters = ClasspathSnapshotBasedIncrementalCompilationApproachParameters(
          /**
           * The classpath snapshots files actual at the moment of compilation
           */
          newClasspathSnapshotFiles = snapshots,

          /**
           * The shrunk classpath snapshot, a result of the previous compilation. Could point to a non-existent file.
           * At the successful end of the compilation, the shrunk version of the [newClasspathSnapshotFiles] will be stored at this path.
           */
          shrunkClasspathSnapshot = incrementalDir.resolve("shrunk-classpath-snapshot.bin").toFile().apply { parentFile?.createDirectory() }
        ),

        options = makeClasspathSnapshotBasedIncrementalCompilationConfiguration().apply {
            // The root project directory, used for computing relative paths in the incremental compilation caches.
            //
            // If it is not specified, incremental compilation caches will be non-relocatable.
            //
            // Managed by [setRootProjectDir]
            // Default value is `null`
            //
            // setRootProjectDir(incrementalDir.resolve("_main").toFile())
            // The build directory, used for computing relative paths in the incremental compilation caches.
            //
            // If it is not specified, incremental compilation caches will be non-relocatable.
            //
            // Managed by [setBuildDir]
            // Default value is `null`
            //
            // setBuildDir(incrementalDir.resolve("_kotlin_incremental").toFile())
            // The directories that the compiler will clean in the case of fallback to non-incremental compilation.
            //
            // The default ones are calculated in the case of a `null` value as a set of the incremental compilation working directory
            // passed to [JvmCompilationConfiguration.useIncrementalCompilation] and the classes output directory from the compiler arguments.
            //
            // If the value is set explicitly, it must contain the above-mentioned default directories.
            //
            //useOutputDirs(emptyList())
            // An indicator whether incremental compilation will analyze Java files precisely for better changes detection
            //
            // Managed by [usePreciseJavaTracking]
            // Default value is defined by implementation of the API
            //
            usePreciseJavaTracking(true)
            // Incremental compilation uses the PersistentHashMap of the intellij platform for storing caches.
            // An indicator whether the changes should remain in memory and not being flushed to the disk until we could mark the compilation as successful.
            //
            // Managed by [keepIncrementalCompilationCachesInMemory]
            // Default value is defined by implementation of the API
            //
            keepIncrementalCompilationCachesInMemory(true)
            // An indicator whether the non-incremental mode of the incremental compiler is forced.
            // The non-incremental mode of the incremental compiler means that during the non-incremental compilation
            // the compiler will collect enoughba information to perform the following builds incrementally.
            //
            // Manager by [forceNonIncrementalMode]
            // By default, the compilation is considered incremental
            //
            forceNonIncrementalMode(false)
            // An indicator whether classpath snapshots comparing should be avoided.
            // Could be used if the check is already performed by the API consumer for the sake of optimization
            //
            // Managed by [assureNoClasspathSnapshotsChanges]
            // By default, the incremental compiler will compare the snapshots itself.
            //
            assureNoClasspathSnapshotsChanges(true)
        }
      )
      useLogger(BasicKotlinLogger(true, "/tmp/kotlin_log/$packagePath/$moduleName/$type.log"))
      useKotlinScriptFilenameExtensions(listOf("kts"))
    }
    val result = service.compileJvm(projectId, executionConfig, compilationConfig, emptyList(), mutableArgs.toList())

    return when(result) {
      CompilationResult.COMPILATION_SUCCESS -> ExitCode.OK
      CompilationResult.COMPILATION_ERROR -> ExitCode.COMPILATION_ERROR
      CompilationResult.COMPILATION_OOM_ERROR -> ExitCode.OOM_ERROR
      CompilationResult.COMPILER_INTERNAL_ERROR -> ExitCode.INTERNAL_ERROR
    }
  }
}
