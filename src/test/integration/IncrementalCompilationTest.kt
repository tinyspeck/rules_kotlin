package io.bazel.kotlin.integration

import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import io.bazel.kotlin.test.BazelIntegrationTestBase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The worker startup flag that turns incremental compilation on. */
private const val INCREMENTAL =
  "--worker_extra_flag=KotlinCompile=--experimental_incremental_compilation"

/**
 * Incremental compilation through a real Bazel: the cache keys of the compile actions, which
 * analysis tests cannot read, cache hits from non-incremental builds, the outputs, and the worker
 * state across builds.
 */
class IncrementalCompilationTest : BazelIntegrationTestBase() {
  private val release by lazy { unpackRelease(env("RULES_KOTLIN_RELEASE")) }
  private val output by lazy { root.resolve("output") }
  private val cache by lazy { root.resolve("cache") }
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

  private val commonFlags by lazy {
    arrayOf(
      "--override_module=rules_kotlin=$release",
      "--@rules_kotlin//kotlin/settings:experimental_build_tools_api=true",
      "--define=kt_trace=1",
    )
  }

  /** Builds //:lib and returns the output lines, with the trace of the compile actions. */
  private fun build(vararg flags: String): List<String> =
    runBazel("build", "--noshow_progress", "--color=no", *commonFlags, *flags, "//:lib")
      .let { (it.stdOut + it.stdErr).toString(UTF_8).lines() }

  /** The KotlinCompile action of //:lib. */
  private fun compileAction(vararg flags: String): Struct {
    val json =
      runBazel(
        "aquery",
        "--output=jsonproto",
        *commonFlags,
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
    return actions.single().structValue
  }

  private fun Struct.executionInfo(): Map<String, String> =
    getFieldsOrThrow("executionInfo").listValue.valuesList.associate {
      it.structValue.getFieldsOrThrow("key").stringValue to
        it.structValue.fieldsMap["value"]?.stringValue.orEmpty()
    }

  private fun compileOutputs(): List<ByteArray> =
    listOf("lib-kt.jar", "lib-kt.jdeps").map {
      Files.readAllBytes(consumer.resolve("bazel-bin").resolve(it))
    }

  @Test
  fun incrementalCompilation() {
    // The startup flag stays out of the cache key: incremental and non-incremental builds share
    // cache entries, and the incremental actions can be uploaded.
    val nonIncremental = compileAction()
    val incremental = compileAction(INCREMENTAL)
    assertEquals(
      nonIncremental.getFieldsOrThrow("actionKey"),
      incremental.getFieldsOrThrow("actionKey"),
    )
    assertFalse("no-remote-cache-upload" in incremental.executionInfo())

    // A non-incremental build fills the cache, as CI would; an incremental build is served from it.
    build("--disk_cache=$cache")
    runBazel("clean")
    val cached = build(INCREMENTAL, "--disk_cache=$cache")
    val text = cached.joinToString("\n")
    assertTrue(text, cached.any { "disk cache hit" in it })
    assertFalse(text, cached.any { it.startsWith("|  state: ") })

    // The first compilation keeps the state, the next one recompiles the changed source only.
    consumer.resolve("A.kt").writeText("package ic\nfun a(): Int = base() + 2\n")
    build(INCREMENTAL)
    consumer.resolve("A.kt").writeText("package ic\nfun a(): Int = 3\n")
    val rebuild = build(INCREMENTAL)
    val modified = rebuild.filter { it.startsWith("|  modified: ") }
    assertEquals(
      rebuild.joinToString("\n"),
      listOf("A.kt"),
      modified.map { it.substringAfterLast('/') },
    )
    assertFalse(rebuild.joinToString("\n"), rebuild.any { it.endsWith("compiling from scratch") })

    // The outputs, including the jdeps of a source that stopped using a dependency, are the
    // bytes of a full compilation.
    val incrementalOutputs = compileOutputs()
    runBazel("clean")
    build()
    compileOutputs().zip(incrementalOutputs).forEach { (full, incremental) ->
      assertArrayEquals(full, incremental)
    }

    // A sandboxed worker's directory is reset before every request: the one worker compiling
    // both targets turns incremental compilation off on its second request.
    consumer.resolve("Base.kt").writeText("package ic\nfun base(): Int = 1\n")
    consumer.resolve("A.kt").writeText("package ic\nfun a(): Int = base() + 3\n")
    val sandboxed =
      build(
        INCREMENTAL,
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
