package io.bazel.kotlin.integration

import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import io.bazel.kotlin.test.BazelIntegrationTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Incremental compilation through a real Bazel: the execution requirements of the compile actions,
 * which analysis tests cannot read, and the worker state across builds.
 */
class IncrementalCompilationTest : BazelIntegrationTestBase() {
  private val release by lazy { unpackRelease(env("RULES_KOTLIN_RELEASE")) }
  private val output by lazy { root.resolve("output") }
  private val consumer: Path by lazy {
    root.resolve("consumer").createDirectories().also { consumer ->
      consumer.resolve("MODULE.bazel").writeText(
        """
        module(name = "incremental_consumer")
        bazel_dep(name = "rules_kotlin", version = "2.2.0")
        """.trimIndent(),
      )
      consumer.resolve("BUILD.bazel").writeText(
        """
        load("@rules_kotlin//kotlin:core.bzl", "define_kt_toolchain")
        load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")
        kt_jvm_library(name = "base", srcs = ["Base.kt"])
        kt_jvm_library(name = "lib", srcs = ["A.kt", "B.kt"], deps = [":base"])
        # --worker_sandboxing sandboxes singleplex workers only.
        define_kt_toolchain(name = "singleplex", experimental_multiplex_workers = False)
        """.trimIndent(),
      )
      consumer.resolve("Base.kt").writeText("package ic\nfun base(): Int = 0\n")
      consumer.resolve("A.kt").writeText("package ic\nfun a(): Int = base() + 1\n")
      consumer.resolve("B.kt").writeText("package ic\nfun b(): Int = a() + 1\n")
    }
  }

  private fun runBazel(vararg args: String): ProcessResult =
    bazel
      .run(
        consumer,
        "--batch",
        "--ignore_all_rc_files",
        "--host_jvm_args=-Djava.net.preferIPv6Addresses=system",
        "--output_user_root=${root.resolve("bazel")}",
        "--output_base=$output",
        *args,
      ).getOrThrow()

  /** Builds //:lib with incremental compilation and returns the trace lines of the actions. */
  private fun build(vararg flags: String): List<String> =
    runBazel(
      "build",
      "--noshow_progress",
      "--color=no",
      "--override_module=rules_kotlin=$release",
      "--define=kt_trace=1",
      "--@rules_kotlin//kotlin/settings:experimental_incremental_compilation=true",
      *flags,
      "//:lib",
    ).let { (it.stdOut + it.stdErr).toString(UTF_8).lines() }

  /** The execution requirements of the target's KotlinCompile action. */
  private fun executionInfo(vararg flags: String): Map<String, String> {
    val json =
      runBazel(
        "aquery",
        "--override_module=rules_kotlin=$release",
        "--output=jsonproto",
        *flags,
        "mnemonic(KotlinCompile, //:lib)",
      ).stdOut.toString(UTF_8)
    val actions =
      Struct
        .newBuilder()
        .also { JsonFormat.parser().merge(json, it) }
        .build()
        .getFieldsOrThrow("actions")
        .listValue.valuesList
    assertEquals("KotlinCompile actions of //:lib", 1, actions.size)
    return actions
      .single()
      .structValue
      .getFieldsOrThrow("executionInfo")
      .listValue.valuesList
      .associate {
        it.structValue.getFieldsOrThrow("key").stringValue to
          it.structValue.fieldsMap["value"]?.stringValue.orEmpty()
      }
  }

  @Test
  fun incrementalCompilation() {
    val incremental =
      executionInfo("--@rules_kotlin//kotlin/settings:experimental_incremental_compilation=true")
    assertEquals("1", incremental["no-remote-cache-upload"])
    assertFalse("no-remote-cache-upload" in executionInfo())

    build()
    consumer.resolve("A.kt").writeText("package ic\nfun a(): Int = base() + 2\n")
    val rebuild = build()
    val modified = rebuild.filter { it.startsWith("|  modified: ") }
    assertEquals(
      rebuild.joinToString("\n"),
      listOf("A.kt"),
      modified.map { it.substringAfterLast('/') },
    )
    assertFalse(rebuild.joinToString("\n"), rebuild.any { it.endsWith("compiling from scratch") })

    // A sandboxed worker's directory is reset before every request: the one worker compiling
    // both targets turns incremental compilation off on its second request.
    consumer.resolve("Base.kt").writeText("package ic\nfun base(): Int = 1\n")
    consumer.resolve("A.kt").writeText("package ic\nfun a(): Int = base() + 3\n")
    val sandboxed =
      build(
        "--extra_toolchains=//:singleplex",
        "--worker_sandboxing",
        "--worker_max_instances=KotlinCompile=1",
      )
    assertTrue(
      sandboxed.joinToString("\n"),
      sandboxed.any { it.startsWith("warning: incremental compilation disabled") },
    )
  }
}
