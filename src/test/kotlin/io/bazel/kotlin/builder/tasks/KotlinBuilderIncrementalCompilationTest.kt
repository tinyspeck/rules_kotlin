package io.bazel.kotlin.builder.tasks

import com.google.common.truth.Truth.assertThat
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.model.JvmCompilationTask
import io.bazel.worker.Status
import io.bazel.worker.WorkerContext
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Files
import java.nio.file.Path

/** Incremental compilation is a worker startup flag, not a flag of the work requests. */
@RunWith(JUnit4::class)
class KotlinBuilderIncrementalCompilationTest {
  private val captured = mutableListOf<JvmCompilationTask>()
  private val capturingExecutor =
    object : JvmTaskExecutor {
      override fun execute(
        context: CompilationTaskContext,
        task: JvmCompilationTask,
      ) {
        captured.add(task)
      }
    }

  private fun args(
    root: Path,
    buildToolsApi: Boolean,
  ): List<String> =
    listOf(
      "--target_label",
      "//some:target",
      "--classpath",
      "dummy.jar",
      "--direct_dependencies",
      "--output",
      root.resolve("out.jar").toString(),
      "--rule_kind",
      "kt_jvm_library",
      "--kotlin_module_name",
      "some_module",
      "--kotlin_api_version",
      "2.0",
      "--kotlin_language_version",
      "2.0",
      "--kotlin_jvm_target",
      "11",
      "--kotlin_debug_tags",
      "--build_kotlin",
      "false",
      "--strict_kotlin_deps",
      "off",
      "--reduced_classpath_mode",
      "off",
      "--instrument_coverage",
      "false",
      "--plugins_payload",
      """{"plugins":[]}""",
    ) +
      if (buildToolsApi) {
        listOf(
          "--btapi_impl_classpath",
          "impl.jar",
          "--internal_jvm_abi_gen_classpath",
          "abi.jar",
          "--internal_kapt_classpath",
          "kapt.jar",
          "--internal_jdeps_gen_classpath",
          "jdeps.jar",
        )
      } else {
        emptyList()
      }

  /** Builds with [builder] and returns the request's output. */
  private fun build(
    builder: KotlinBuilder,
    buildToolsApi: Boolean,
  ): String {
    val root = Files.createTempDirectory("KotlinBuilderIncrementalCompilationTest")
    return WorkerContext.run(named = "test") {
      doTask("build", sandboxDir = root) { taskContext ->
        builder.build(taskContext, args = args(root, buildToolsApi))
        Status.SUCCESS
      }.log.out
        .toString()
    }
  }

  @Test
  fun `the startup flag makes the Build Tools API tasks compile incrementally`() {
    build(KotlinBuilder(capturingExecutor, incrementalCompilation = true), buildToolsApi = true)

    assertThat(captured.single().info.incrementalCompilation).isTrue()
  }

  @Test
  fun `tasks compile non-incrementally without the startup flag`() {
    build(KotlinBuilder(capturingExecutor), buildToolsApi = true)

    assertThat(captured.single().info.incrementalCompilation).isFalse()
  }

  @Test
  fun `a request flag cannot turn incremental compilation on`() {
    val root = Files.createTempDirectory("KotlinBuilderIncrementalCompilationTest")
    WorkerContext.run(named = "test") {
      doTask("build", sandboxDir = root) { taskContext ->
        KotlinBuilder(capturingExecutor).build(
          taskContext,
          args = args(root, buildToolsApi = true) + listOf("--incremental_compilation", "true"),
        )
        Status.SUCCESS
      }
    }

    assertThat(captured.single().info.incrementalCompilation).isFalse()
  }

  @Test
  fun `legacy tasks compile non-incrementally and warn once`() {
    val builder = KotlinBuilder(capturingExecutor, incrementalCompilation = true)

    val first = build(builder, buildToolsApi = false)
    val second = build(builder, buildToolsApi = false)

    assertThat(captured.map { it.info.incrementalCompilation }).containsExactly(false, false)
    assertThat(first).contains("warning: incremental compilation disabled")
    assertThat(first).contains("experimental_build_tools_api=true")
    assertThat(second).doesNotContain("warning: incremental compilation disabled")
  }
}
