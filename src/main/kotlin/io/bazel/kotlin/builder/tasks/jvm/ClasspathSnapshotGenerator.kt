package io.bazel.kotlin.builder.tasks.jvm

import java.nio.file.Path
import kotlin.system.measureTimeMillis
import org.jetbrains.kotlin.buildtools.api.CompilationService
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import java.io.File
import java.util.logging.Logger
import kotlin.io.path.exists

@OptIn(ExperimentalBuildToolsApi::class)
class ClasspathSnapshotGenerator(
    private val inputJar: Path,
    private val outputSnapshot: Path,
    private val granularity: SnapshotGranularity
) {

    fun run() {
        if (outputSnapshot.exists()) { return }
        val timeSpent = measureTimeMillis {
            val compilationService = CompilationService.loadImplementation(this.javaClass.classLoader!!)
            val snapshot =
                compilationService.calculateClasspathSnapshot(
                    inputJar.toFile(), granularity.toClassSnapshotGranularity)
            snapshot.saveSnapshot(outputSnapshot.toFile())
        }
        LOG.info("$timeSpent ms for input jar: $inputJar")
    }

    companion object {
      private val LOG: BasicKotlinLogger = BasicKotlinLogger(file = "/Users/ekerber/timing")
    }
}
class BasicKotlinLogger(override val isDebugEnabled: Boolean = true, file: String = "/Users/ekerber/kotlin.log") :
  KotlinLogger {
  val file = File(file)
  val b = "\n"
  override fun warn(msg: String) {
    file.outputStream().bufferedWriter().use { it.write(msg + b) }
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
