/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
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

import java.io.PrintStream

/** What to compile and where the output goes. */
interface CompilationUnit {
  val sources: List<String>
  val classpath: List<String>
  val friendPaths: List<String>
  val destination: String
}

/** The compiler configuration the rules manage for the compilation. */
interface CompilerConfiguration {
  val moduleName: String
  val jvmTarget: String
  val apiVersion: String
  val languageVersion: String

  /** The user's pass-through compiler flags. */
  val passthroughArguments: List<String>
  val verbose: Boolean
}

/** One configured compiler plugin; each option is a `key=value` string. */
interface CompilerPluginSpec {
  val id: String
  val classpath: List<String>
  val options: List<String>
}

/**
 * The snapshot-based incremental compilation of a [CompilationUnit] whose destination holds the
 * outputs of the previous successful compilation.
 */
interface IncrementalCompilationSpec {
  /** The directory the incremental compiler keeps its caches in. */
  val workingDirectory: String

  /**
   * Whether [modifiedSources] and [removedSources] describe every source change since the previous
   * successful compilation. When false, the compiler rebuilds the unit from scratch and records
   * the state the following compilations are incremental against.
   */
  val sourceChangesKnown: Boolean
  val modifiedSources: List<String>
  val removedSources: List<String>

  /** The classpath entry snapshots (see [KotlinBtapiCompiler.snapshotClasspathEntry]), in classpath order. */
  val classpathSnapshots: List<String>

  /** Directories cleaned together with the destination when the compiler falls back to a rebuild. */
  val additionalOutputDirectories: List<String>
}

/**
 * The typed Build Tools API compilation contract between the worker and the compiler classloader.
 * Provides structured API to invoke compiler as an in-process utility.
 */
interface KotlinBtapiCompiler {
  /** Compiles the unit; incrementally when [incrementalCompilation] is given. */
  fun exec(
    errStream: PrintStream,
    compilationUnit: CompilationUnit,
    configuration: CompilerConfiguration,
    plugins: List<CompilerPluginSpec>,
    incrementalCompilation: IncrementalCompilationSpec?,
  ): Int

  /**
   * Writes the incremental compilation snapshot of one classpath entry (a jar or a class
   * directory) to [snapshot]. Class-level granularity yields smaller snapshots and suits rarely
   * changing entries; member-level granularity recompiles less when the entry changes.
   */
  fun snapshotClasspathEntry(
    classpathEntry: String,
    snapshot: String,
    classLevelGranularity: Boolean,
  )
}
