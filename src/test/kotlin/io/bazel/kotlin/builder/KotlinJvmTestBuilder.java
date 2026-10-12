/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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
package io.bazel.kotlin.builder;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.bazel.kotlin.builder.Deps.AnnotationProcessor;
import io.bazel.kotlin.builder.Deps.Dep;
import io.bazel.kotlin.builder.tasks.jvm.InternalCompilerPlugins;
import io.bazel.kotlin.builder.tasks.jvm.KotlinJvmTaskExecutor;
import io.bazel.kotlin.builder.tasks.jvm.btapi.BtapiTaskExecutor;
import io.bazel.kotlin.builder.tasks.jvm.btapi.IncrementalCompilation;
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext;
import io.bazel.kotlin.builder.toolchain.KotlinToolchain;
import io.bazel.kotlin.model.CompilationTaskInfo;
import io.bazel.kotlin.model.JvmCompilationTask;
import io.bazel.kotlin.model.KotlinToolchainInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class KotlinJvmTestBuilder extends KotlinAbstractTestBuilder<JvmCompilationTask> {

    @SuppressWarnings({"unused", "WeakerAccess"})
    public static Dep
            KOTLIN_ANNOTATIONS = Dep.fromLabel("//kotlin/compiler:annotations"),
            KOTLIN_STDLIB = Dep.fromLabel("//kotlin/compiler:kotlin-stdlib"),
            KOTLIN_STDLIB_JDK7 = Dep.fromLabel("//kotlin/compiler:kotlin-stdlib-jdk7"),
            KOTLIN_STDLIB_JDK8 = Dep.fromLabel("//kotlin/compiler:kotlin-stdlib-jdk8"),
            JVM_ABI_GEN = Dep.fromLabel("//kotlin/compiler:jvm-abi-gen");

    private static final JvmCompilationTask.Builder taskBuilder = JvmCompilationTask.newBuilder();
    /** Digests of the jars, which the tests do not rewrite in place, by path, size and mtime. */
    private static final Map<String, String> JAR_DIGESTS = new ConcurrentHashMap<>();
    private static final EnumSet<DirectoryType> ALL_DIRECTORY_TYPES =
            EnumSet.of(
                    DirectoryType.SOURCES,
                    DirectoryType.CLASSES,
                    DirectoryType.JAVA_CLASSES,
                    DirectoryType.ABI_CLASSES,
                    DirectoryType.SOURCE_GEN,
                    DirectoryType.JAVA_SOURCE_GEN,
                    DirectoryType.GENERATED_CLASSES,
                    DirectoryType.TEMP,
                    DirectoryType.COVERAGE_METADATA);

    private final TaskBuilder taskBuilderInstance = new TaskBuilder();
    private static KotlinJvmTaskExecutor jvmTaskExecutor;
    private static BtapiTaskExecutor btapiTaskExecutor;
    private static Path incrementalCompilationRoot;
    private boolean sendInputDigests = true;
    private static KotlinToolchainInfo.BtapiRuntime btapiRuntime;

    @Override
    void setupForNext(CompilationTaskInfo.Builder taskInfo) {
        taskBuilder.clear().setInfo(taskInfo);

        DirectoryType.createAll(instanceRoot(), ALL_DIRECTORY_TYPES);

        taskBuilder.getInputsBuilder()
            .addClasspath(KOTLIN_STDLIB.singleCompileJar())
            .addClasspath(KOTLIN_STDLIB_JDK7.singleCompileJar())
            .addClasspath(KOTLIN_STDLIB_JDK8.singleCompileJar());

        taskBuilder
                .getDirectoriesBuilder()
                .setClasses(directory(DirectoryType.CLASSES).toAbsolutePath().toString())
                .setJavaClasses(directory(DirectoryType.JAVA_CLASSES).toAbsolutePath().toString())
                .setAbiClasses(directory(DirectoryType.ABI_CLASSES).toAbsolutePath().toString())
                .setGeneratedSources(directory(DirectoryType.SOURCE_GEN).toAbsolutePath().toString())
                .setGeneratedJavaSources(directory(DirectoryType.JAVA_SOURCE_GEN).toAbsolutePath().toString())
                .setGeneratedStubClasses(directory(DirectoryType.GENERATED_STUBS).toAbsolutePath().toString())
                .setTemp(directory(DirectoryType.TEMP).toAbsolutePath().toString())
                .setGeneratedClasses(directory(DirectoryType.GENERATED_CLASSES).toAbsolutePath().toString())
                .setCoverageMetadataClasses(directory(DirectoryType.COVERAGE_METADATA).toAbsolutePath().toString());
    }

    @Override
    public JvmCompilationTask buildTask() {
        return taskBuilder.build();
    }

    @SafeVarargs
    public final Dep runCompileTask(Consumer<TaskBuilder>... setup) {
        // Mirror the worker's dispatch: tasks flagged for the Build Tools API run through the
        // typed executor, everything else through the legacy executor.
        resetForNext();
        sendInputDigests = true;
        return executeTask(KotlinJvmTestBuilder::dispatch, setup);
    }

    /**
     * Mirrors the worker protocol: Bazel sends a digest of every input, computed with its
     * --digest_function. Only incremental compilations use them; the others skip the hashing.
     */
    @Override
    Map<String, String> inputDigests(JvmCompilationTask task) {
        if (!sendInputDigests || !task.getInfo().getIncrementalCompilation()) {
            return Collections.emptyMap();
        }
        JvmCompilationTask.Inputs inputs = task.getInputs();
        KotlinToolchainInfo.BtapiRuntime btapi = task.getInfo().getToolchainInfo().getBtapi();
        Map<String, String> digests = new HashMap<>();
        Stream.of(inputs.getKotlinSourcesList(), inputs.getJavaSourcesList())
                .flatMap(List::stream)
                .forEach(source -> digests.put(source, sha256(Paths.get(source))));
        Stream.of(
                        inputs.getClasspathList(),
                        inputs.getCompilerPluginClasspathList(),
                        btapi.getApiImplClasspathList(),
                        btapi.getJvmAbiGenClasspathList(),
                        btapi.getKaptClasspathList(),
                        btapi.getJdepsGenClasspathList(),
                        inputs.getPluginsList().stream()
                                .flatMap(plugin -> plugin.getClasspathList().stream())
                                .collect(Collectors.toList()))
                .flatMap(List::stream)
                .filter(jar -> Files.isRegularFile(Paths.get(jar)))
                .forEach(jar -> digests.put(jar, jarDigest(Paths.get(jar))));
        return digests;
    }

    private static String jarDigest(Path jar) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(jar, BasicFileAttributes.class);
            String key = jar + " " + attributes.size() + " " + attributes.lastModifiedTime();
            return JAR_DIGESTS.computeIfAbsent(key, k -> sha256(jar));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Starts over with a new worker and an empty incremental compilation root, as a restarted
     * worker in a new output base.
     */
    public static void restartWorker() {
        btapiTaskExecutor = null;
    }

    /** The incremental compilation root of the current worker. */
    public static Path incrementalCompilationRoot() {
        btapiTaskExecutor();
        return incrementalCompilationRoot;
    }

    private static KotlinJvmTaskExecutor jvmTaskExecutor() {
        if (jvmTaskExecutor == null) {
            KotlinToolchain toolchain = toolchainForTest();
            InternalCompilerPlugins plugins = new InternalCompilerPlugins(
                    toolchain.getJvmAbiGen(),
                    toolchain.getKapt3Plugin(),
                    toolchain.getJdepsGen()
            );
            jvmTaskExecutor = new KotlinJvmTaskExecutor(toolchain, plugins);
        }
        return jvmTaskExecutor;
    }

    private static BtapiTaskExecutor btapiTaskExecutor() {
        if (btapiTaskExecutor == null) {
            incrementalCompilationRoot =
                    Paths.get(System.getenv("TEST_TMPDIR"), "kotlin_ic", UUID.randomUUID().toString());
            btapiTaskExecutor = new BtapiTaskExecutor(
                    toolchainForTest().getBtapiClassLoader(),
                    new IncrementalCompilation(incrementalCompilationRoot));
        }
        return btapiTaskExecutor;
    }

    /**
     * The Build Tools API runtime the worker would receive through the --btapi_* and --internal_*
     * flags: the runtime of the default implementation repository, as the toolchain composes it.
     * Tests run the embeddable compiler family (the dialect the Maven-published compiler plugins
     * are built against) with the matching embeddable internal plugin variants, while the default
     * toolchain exercises the CLI-distribution family through the integration fixtures.
     */
    static KotlinToolchainInfo.BtapiRuntime btapiRuntimeForTest() {
        if (btapiRuntime == null) {
            btapiRuntime = KotlinToolchainInfo.BtapiRuntime.newBuilder()
                    .addApiImplClasspath(Dep.fromLabel("@btapi_impl//:kotlin-build-tools-impl.jar").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@btapi_impl//:kotlin-daemon-client.jar").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@btapi_impl//:kotlin-stdlib.jar").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@btapi_impl//:kotlin-reflect.jar").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@btapi_impl//:kotlin-script-runtime.jar").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@btapi_impl//:kotlin-tooling-core.jar").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@kotlinx_coroutines_core_jvm//file").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("//kotlin/compiler:annotations").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@kotlinx_serialization_core_jvm//file").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@kotlinx_serialization_json//file").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@kotlinx_serialization_json_jvm//file").singleCompileJar())
                    .addApiImplClasspath(Dep.fromLabel("@btapi_impl//:kotlin-compiler-embeddable.jar").singleCompileJar())
                    .addJvmAbiGenClasspath(Dep.fromLabel("@btapi_impl//:jvm-abi-gen.jar").singleCompileJar())
                    .addKaptClasspath(Dep.fromLabel("@btapi_impl//:kotlin-annotation-processing-embeddable.jar").singleCompileJar())
                    .addJdepsGenClasspath(Dep.fromLabel("//src/main/kotlin:jdeps-gen-embeddable").singleCompileJar())
                    .build();
        }
        return btapiRuntime;
    }

    /**
     * The class-file major version of a compiled class, read from the CLASSES output directory.
     */
    public final int classFileMajorVersion(String relativeClassPath) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(
                    directory(DirectoryType.CLASSES).resolve(relativeClassPath));
            return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Runs the previous task again, as Bazel runs the action of the same target after its inputs
     * changed: the per-action directories and the outputs start out empty, while the sources and the
     * incremental compilation state of the target are kept.
     *
     * @param changes the changes to the task, e.g. updated or removed sources.
     */
    @SafeVarargs
    public final Dep rerunCompileTask(Consumer<TaskBuilder>... changes) {
        EnumSet<DirectoryType> actionDirectories = EnumSet.copyOf(ALL_DIRECTORY_TYPES);
        actionDirectories.remove(DirectoryType.SOURCES);
        actionDirectories.forEach(type -> deleteRecursively(directory(type)));
        DirectoryType.createAll(instanceRoot(), actionDirectories);
        JvmCompilationTask.Outputs outputs = taskBuilder.getOutputs();
        Stream.of(outputs.getJar(), outputs.getAbijar(), outputs.getJdeps(), outputs.getSrcjar())
                .filter(p -> !p.isEmpty())
                .forEach(p -> deleteRecursively(Paths.get(p)));
        return executeTask(KotlinJvmTestBuilder::dispatch, changes);
    }

    private static void dispatch(CompilationTaskContext context, JvmCompilationTask task) {
        if (task.getInfo().getToolchainInfo().hasBtapi()) {
            btapiTaskExecutor().execute(context, task);
        } else {
            jvmTaskExecutor().execute(context, task);
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Dep executeTask(
            BiConsumer<CompilationTaskContext, JvmCompilationTask> executor,
            Consumer<TaskBuilder>[] setup) {
        Stream.of(setup).forEach(it -> it.accept(taskBuilderInstance));
        return runCompileTask(
                (taskContext, task) -> {
                    executor.accept(taskContext, task);

                    JvmCompilationTask.Outputs outputs = task.getOutputs();
                    assertFilesExist(
                            Stream.of(
                                            outputs.getAbijar(),
                                            outputs.getJar(),
                                            outputs.getJdeps(),
                                            outputs.getSrcjar())
                                    .filter(p -> !p.isEmpty())
                                    .toArray(String[]::new)
                    );

                    return Dep.builder()
                            .label(task.getInfo().getLabel())
                            .compileJars(ImmutableList.of(
                                    outputs.getAbijar().isEmpty() ? outputs.getJar() : outputs.getAbijar()
                            ))
                            .jdeps(outputs.getJdeps())
                            .runtimeDeps(ImmutableList.copyOf(task.getInputs().getClasspathList()))
                            .sourceJar(outputs.getSrcjar())
                            .build();
                });
    }

    public void tearDown() {
        jvmTaskExecutor = null;
    }

    public class TaskBuilder {
        TaskBuilder() {
        }

        public void setLabel(String label) {
            taskBuilder.getInfoBuilder().setLabel(label);
        }

        public void addSource(String filename, String... lines) {
            String pathAsString = writeSourceFile(filename, lines).toString();
            if (pathAsString.endsWith(".kt")) {
                taskBuilder.getInputsBuilder().addKotlinSources(pathAsString);
            } else if (pathAsString.endsWith(".java")) {
                taskBuilder.getInputsBuilder().addJavaSources(pathAsString);
            } else {
                throw new RuntimeException("unhandled file type: " + pathAsString);
            }
        }

        /** Replaces the content of a source added before. */
        public void updateSource(String filename, String... lines) {
            String path = writeSourceFile(filename, lines).toString();
            Preconditions.checkState(
                    taskBuilder.getInputs().getKotlinSourcesList().contains(path)
                            || taskBuilder.getInputs().getJavaSourcesList().contains(path),
                    "not a source of the task: %s", path);
        }

        /** Removes a source added before from the task and deletes it. */
        public void removeSource(String filename) {
            String path = directory(DirectoryType.SOURCES).resolve(filename).toAbsolutePath().toString();
            JvmCompilationTask.Inputs.Builder inputs = taskBuilder.getInputsBuilder();
            List<String> kotlinSources = new ArrayList<>(inputs.getKotlinSourcesList());
            List<String> javaSources = new ArrayList<>(inputs.getJavaSourcesList());
            Preconditions.checkState(
                    kotlinSources.remove(path) || javaSources.remove(path),
                    "not a source of the task: %s", path);
            inputs.clearKotlinSources().addAllKotlinSources(kotlinSources);
            inputs.clearJavaSources().addAllJavaSources(javaSources);
            deleteRecursively(Paths.get(path));
        }

        public TaskBuilder incrementalCompilation() {
            taskBuilder.getInfoBuilder().setIncrementalCompilation(true);
            return this;
        }

        /** Runs the task without input digests, as Bazel runs it outside a persistent worker. */
        public TaskBuilder withoutInputDigests() {
            sendInputDigests = false;
            return this;
        }

        public TaskBuilder compileJava() {
            return this;
        }

        public TaskBuilder compileKotlin() {
            taskBuilder.getInfoBuilder().addDebug("trace").addDebug("timings");
            taskBuilder.setCompileKotlin(true);
            return this;
        }

        public TaskBuilder coverage() {
            taskBuilder.setInstrumentCoverage(true);
            return this;
        }

        public void addAnnotationProcessors(AnnotationProcessor... annotationProcessors) {
            Preconditions.checkState(
                    taskBuilder.getInputs().getProcessorsList().isEmpty(), "processors already set");
            HashSet<String> processorClasses = new HashSet<>();
            taskBuilder
                    .getInputsBuilder()
                    .addAllProcessorpaths(
                            Stream.of(annotationProcessors)
                                    .peek(it -> processorClasses.add(it.processClass()))
                                    .flatMap(it -> it.processorPath().stream())
                                    .distinct()
                                    .collect(Collectors.toList()))
                    .addAllProcessors(processorClasses);
        }

        public void addDirectDependencies(Dep... dependencies) {
            Dep.classpathOf(dependencies).forEach(dependency -> {
                taskBuilder.getInputsBuilder().addClasspath(dependency);
                taskBuilder.getInputsBuilder().addDirectDependencies(dependency);
            });
        }

        /** Keeps direct dependencies added before on the classpath as transitive dependencies. */
        public void makeTransitive(Dep... dependencies) {
            List<String> jars = Dep.classpathOf(dependencies).collect(Collectors.toList());
            JvmCompilationTask.Inputs.Builder inputs = taskBuilder.getInputsBuilder();
            List<String> direct = new ArrayList<>(inputs.getDirectDependenciesList());
            Preconditions.checkState(direct.removeAll(jars), "not direct dependencies: %s", jars);
            inputs.clearDirectDependencies().addAllDirectDependencies(direct);
        }

        /** Removes dependencies added before from the classpath. */
        public void removeDependencies(Dep... dependencies) {
            List<String> jars = Dep.classpathOf(dependencies).collect(Collectors.toList());
            JvmCompilationTask.Inputs.Builder inputs = taskBuilder.getInputsBuilder();
            List<String> direct = new ArrayList<>(inputs.getDirectDependenciesList());
            List<String> classpath = new ArrayList<>(inputs.getClasspathList());
            Preconditions.checkState(classpath.removeAll(jars), "not dependencies: %s", jars);
            direct.removeAll(jars);
            inputs.clearDirectDependencies().addAllDirectDependencies(direct);
            inputs.clearClasspath().addAllClasspath(classpath);
        }

        public void addTransitiveDependencies(Dep... dependencies) {
            Dep.classpathOf(dependencies).forEach(dependency -> {
                taskBuilder.getInputsBuilder().addClasspath(dependency);
            });
        }

        public TaskBuilder outputSrcJar() {
            taskBuilder.getOutputsBuilder()
                    .setSrcjar(instanceRoot().resolve("jar_file-sources.jar").toAbsolutePath().toString());
            return this;
        }

        public TaskBuilder outputJar() {
            taskBuilder.getOutputsBuilder()
                    .setJar(instanceRoot().resolve("jar_file.jar").toAbsolutePath().toString());
            return this;
        }

        public TaskBuilder outputJdeps() {
            taskBuilder.getOutputsBuilder()
                    .setJdeps(instanceRoot().resolve("jdeps_file.jdeps").toAbsolutePath().toString());
            return this;
        }

        public TaskBuilder kotlinStrictDeps(String level) {
            taskBuilder.getInfoBuilder().setStrictKotlinDeps(level);
            return this;
        }

        public TaskBuilder outputAbiJar() {
            taskBuilder.getOutputsBuilder()
                    .setAbijar(instanceRoot().resolve("abi.jar").toAbsolutePath().toString());
            return this;
        }

        public TaskBuilder publicOnlyAbiJar() {
            taskBuilder.getInfoBuilder().setTreatInternalAsPrivateInAbiJar(true).setRemovePrivateClassesInAbiJar(true);
            return this;
        }

        public TaskBuilder generatedSourceJar() {
            taskBuilder.getOutputsBuilder()
                    .setGeneratedJavaSrcJar(instanceRoot().resolve("gen-src.jar").toAbsolutePath().toString());
            return this;
        }

        public TaskBuilder ktStubsJar() {
            taskBuilder.getOutputsBuilder()
                    .setGeneratedJavaStubJar(instanceRoot().resolve("kt-stubs.jar").toAbsolutePath().toString());
            return this;
        }

        public TaskBuilder incrementalData() {
            taskBuilder.getOutputsBuilder()
                    .setGeneratedClassJar(instanceRoot().resolve("incremental.jar").toAbsolutePath().toString());
            return this;
        }

        public TaskBuilder useK2() {
            taskBuilder.getInfoBuilder()
                    .getToolchainInfoBuilder()
                    .getCommonBuilder()
                    .setLanguageVersion("2.0");
            return this;
        }

        public TaskBuilder useBuildToolsApi() {
            taskBuilder.getInfoBuilder().getToolchainInfoBuilder().setBtapi(btapiRuntimeForTest());
            return this;
        }

        public TaskBuilder addPassthroughFlags(String... flags) {
            taskBuilder.getInfoBuilder().addAllPassthroughFlags(Arrays.asList(flags));
            return this;
        }
    }
}
