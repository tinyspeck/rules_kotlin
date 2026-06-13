/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
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
package io.bazel.kotlin.ksp2

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KspGradleLogger
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import java.io.File
import java.io.OutputStream
import java.util.ServiceLoader

/**
 * Wrapper for KSP2 invocation using direct API calls.
 *
 * This class is compiled against KSP2 classes (via neverlink_deps) and loaded
 * at runtime in a classloader that has the KSP2 jars. This follows the same
 * pattern as BuildToolsAPICompiler.
 */
@Suppress("unused")
class Ksp2Invoker(
  private val classLoader: ClassLoader,
) {
  /**
   * Execute KSP2 with the given configuration.
   *
   * @param logLevel Logger level (0=ERROR, 1=WARN, 2=INFO, 3=LOGGING)
   * @param processorOptions Options passed to symbol processors
   * @return Exit code (0 for success)
   */
  fun execute(
    moduleName: String,
    sourceRoots: List<File>,
    javaSourceRoots: List<File>,
    libraries: List<File>,
    kotlinOutputDir: File,
    javaOutputDir: File,
    classOutputDir: File,
    resourceOutputDir: File,
    cachesDir: File,
    projectBaseDir: File,
    outputBaseDir: File,
    jvmTarget: String?,
    languageVersion: String?,
    apiVersion: String?,
    jdkHome: File?,
    processorOptions: Map<String, String> = emptyMap(),
    logLevel: Int = 1,
  ): Int {
    // Load processors via ServiceLoader from the provided classloader.
    //
    // Each provider is wrapped so the processor sees a CodeGenerator that
    // tolerates a file being "created" more than once in a single KSP run.
    // Some aggregating processors (notably Anvil's ContributesBindingCodeGen)
    // re-emit the same generated file when KSP runs an extra round (triggered by
    // an unrelated processor deferring symbols), which otherwise throws
    // FileAlreadyExistsException. The repeat write is byte-identical, so
    // dropping it is safe and matches single-pass (e.g. embedded/Gradle) output.
    val processors =
      ServiceLoader.load(SymbolProcessorProvider::class.java, classLoader)
        .toList()
        .map { DedupingProviderWrapper(it) }

    // Build KSP2 configuration
    val kspConfig =
      KSPJvmConfig
        .Builder()
        .apply {
          this.moduleName = moduleName
          this.sourceRoots = sourceRoots
          this.javaSourceRoots = javaSourceRoots
          this.libraries = libraries
          this.kotlinOutputDir = kotlinOutputDir
          this.javaOutputDir = javaOutputDir
          this.classOutputDir = classOutputDir
          this.resourceOutputDir = resourceOutputDir
          this.cachesDir = cachesDir
          this.projectBaseDir = projectBaseDir
          this.outputBaseDir = outputBaseDir
          jvmTarget?.let { this.jvmTarget = it }
          languageVersion?.let { this.languageVersion = it }
          apiVersion?.let { this.apiVersion = it }
          jdkHome?.let { this.jdkHome = it }
          this.processorOptions = processorOptions
          this.mapAnnotationArgumentsInJava = true
        }.build()

    // Create logger and execute
    val logger = KspGradleLogger(logLevel)
    val ksp = KotlinSymbolProcessing(kspConfig, processors, logger)

    return ksp.execute().code
  }

  /**
   * Wraps a [SymbolProcessorProvider] so the created processor receives an
   * environment whose [CodeGenerator] de-duplicates repeated file creations
   * within a single KSP run (see note at the call site).
   */
  private class DedupingProviderWrapper(
    private val delegate: SymbolProcessorProvider,
  ) : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
      val wrappedEnv =
        SymbolProcessorEnvironment(
          options = environment.options,
          kotlinVersion = environment.kotlinVersion,
          codeGenerator = DedupingCodeGenerator(environment.codeGenerator),
          logger = environment.logger,
          apiVersion = environment.apiVersion,
          compilerVersion = environment.compilerVersion,
          platforms = environment.platforms,
          kspVersion = environment.kspVersion,
        )
      return delegate.create(wrappedEnv)
    }
  }

  /**
   * Delegating [CodeGenerator] that returns a no-op stream when a processor
   * asks to create a file it already created in this run, instead of letting
   * the underlying generator throw FileAlreadyExistsException. All other
   * behavior is delegated unchanged.
   */
  private class DedupingCodeGenerator(
    private val delegate: CodeGenerator,
  ) : CodeGenerator {
    private val seen = mutableSetOf<String>()

    override fun createNewFile(
      dependencies: Dependencies,
      packageName: String,
      fileName: String,
      extensionName: String,
    ): OutputStream {
      if (!seen.add("$packageName/$fileName.$extensionName")) {
        return OutputStream.nullOutputStream()
      }
      return delegate.createNewFile(dependencies, packageName, fileName, extensionName)
    }

    override fun createNewFileByPath(
      dependencies: Dependencies,
      path: String,
      extensionName: String,
    ): OutputStream {
      if (!seen.add("$path.$extensionName")) {
        return OutputStream.nullOutputStream()
      }
      return delegate.createNewFileByPath(dependencies, path, extensionName)
    }

    override fun associate(
      sources: List<KSFile>,
      packageName: String,
      fileName: String,
      extensionName: String,
    ) = delegate.associate(sources, packageName, fileName, extensionName)

    override fun associateByPath(
      sources: List<KSFile>,
      path: String,
      extensionName: String,
    ) = delegate.associateByPath(sources, path, extensionName)

    override fun associateWithClasses(
      classes: List<KSClassDeclaration>,
      packageName: String,
      fileName: String,
      extensionName: String,
    ) = delegate.associateWithClasses(classes, packageName, fileName, extensionName)

    override fun associateWithFunctions(
      functions: List<KSFunctionDeclaration>,
      packageName: String,
      fileName: String,
      extensionName: String,
    ) = delegate.associateWithFunctions(functions, packageName, fileName, extensionName)

    override fun associateWithProperties(
      properties: List<KSPropertyDeclaration>,
      packageName: String,
      fileName: String,
      extensionName: String,
    ) = delegate.associateWithProperties(properties, packageName, fileName, extensionName)

    override val generatedFile: Collection<File>
      get() = delegate.generatedFile
  }
}
