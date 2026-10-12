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
package io.bazel.kotlin.builder.tasks.jvm;

import com.google.devtools.build.lib.view.proto.Deps.Dependencies;
import com.google.devtools.build.lib.view.proto.Deps.Dependency;
import io.bazel.kotlin.builder.Deps.Dep;
import io.bazel.kotlin.builder.DirectoryType;
import io.bazel.kotlin.builder.KotlinJvmTestBuilder;
import io.bazel.kotlin.builder.toolchain.CompilationStatusException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** Incremental compilation through the Build Tools API, across runs of the same target. */
@RunWith(JUnit4.class)
public class KotlinBuilderJvmBtaIncrementalTest {
    private static final KotlinJvmTestBuilder ctx = new KotlinJvmTestBuilder();

    private static final String[] A = {"package ic", "fun a(): Int = 1"};
    private static final String[] B = {"package ic", "fun b(): Int = a() + 1"};
    private static final String[] C = {"package ic", "class C { fun c() = \"c\" }"};

    private static void compileABC(boolean abiJar) {
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.addSource("A.kt", A);
                    c.addSource("B.kt", B);
                    c.addSource("C.kt", C);
                    c.outputJar();
                    c.outputJdeps();
                    if (abiJar) {
                        c.outputAbiJar();
                    }
                });
    }

    /** The file names of the sources the compiler compiled, over every compile iteration. */
    private static List<String> compiledSources() {
        List<String> compiled = new ArrayList<>();
        boolean inIteration = false;
        for (String line : ctx.outLines()) {
            if (line.equals("Compile iteration:")) {
                inIteration = true;
            } else if (inIteration && line.startsWith("  ")) {
                String path = line.trim().split(" <- ")[0];
                compiled.add(Paths.get(path).getFileName().toString());
            } else {
                inIteration = false;
            }
        }
        return compiled;
    }

    private static List<String> jarEntries(String jar) {
        try (JarFile file = new JarFile(jar)) {
            return file.stream().map(e -> e.getName()).collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Dependencies jdeps(Dep dep) {
        try (InputStream in = Files.newInputStream(Paths.get(dep.jdeps()))) {
            return Dependencies.parseFrom(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void testFirstCompilationIsComplete() {
        compileABC(false);

        assertThat(ctx.outLines()).contains("|  source changes unknown");
        ctx.assertFilesExist(DirectoryType.CLASSES, "ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    @Test
    public void testUnchangedSourcesCompileNothing() {
        compileABC(false);

        Dep dep = ctx.rerunCompileTask();

        assertThat(compiledSources()).isEmpty();
        ctx.assertFilesExist(DirectoryType.CLASSES, "ic/AKt.class", "ic/BKt.class", "ic/C.class");
        assertThat(jarEntries(dep.singleCompileJar()))
                .containsAtLeast("ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    @Test
    public void testBodyChangeCompilesTheChangedSourceOnly() {
        compileABC(false);

        ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 2"));

        assertThat(compiledSources()).containsExactly("A.kt");
    }

    @Test
    public void testSignatureChangeCompilesTheDependentSources() {
        compileABC(true);

        // B.kt is unchanged, but its call to a() no longer links.
        ctx.rerunCompileTask(
                c -> c.updateSource("A.kt", "package ic", "fun a(offset: Int = 0): Int = 1 + offset"));
        assertThat(compiledSources()).containsAtLeast("A.kt", "B.kt");
        assertThat(compiledSources()).doesNotContain("C.kt");
        // The ABI of every compile iteration is kept.
        ctx.assertFilesExist(DirectoryType.ABI_CLASSES, "ic/AKt.class", "ic/BKt.class", "ic/C.class");

        ctx.rerunCompileTask(
                c -> c.updateSource(
                        "A.kt", "package ic", "fun a(offset: Int = 0): Int = 1 + offset", "fun x() = 0"));
        assertThat(compiledSources()).doesNotContain("C.kt");
    }

    @Test
    public void testRemovedSourceRemovesItsClasses() {
        compileABC(true);

        Dep dep = ctx.rerunCompileTask(c -> c.removeSource("C.kt"));

        ctx.assertFilesDoNotExist(DirectoryType.CLASSES, "ic/C.class");
        ctx.assertFilesDoNotExist(DirectoryType.ABI_CLASSES, "ic/C.class");
        ctx.assertFilesExist(DirectoryType.CLASSES, "ic/AKt.class", "ic/BKt.class");
        assertThat(jarEntries(dep.singleCompileJar())).doesNotContain("ic/C.class");
    }

    @Test
    public void testAbiJarKeepsTheClassesOfUnchangedSources() {
        compileABC(true);
        List<String> before = jarEntries(ctx.rerunCompileTask().singleCompileJar());

        Dep dep = ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 3"));

        assertThat(compiledSources()).containsExactly("A.kt");
        assertThat(jarEntries(dep.singleCompileJar())).containsExactlyElementsIn(before);
        assertThat(before)
                .containsAtLeast(
                        "ic/AKt.class",
                        "ic/BKt.class",
                        "ic/C.class",
                        "META-INF/some_bogus_module.kotlin_module");
    }

    @Test
    public void testJdepsKeepTheDependenciesOfUnchangedSources() {
        Dep dependency = ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.compileKotlin();
                    c.addSource("Dependency.kt", "package dep", "class Dependency");
                    c.outputJar();
                });
        Dep first = ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.addDirectDependencies(dependency);
                    c.addSource("A.kt", A);
                    c.addSource("UsesDependency.kt", "package ic", "fun use() = dep.Dependency()");
                    c.outputJar();
                    c.outputJdeps();
                });
        assertThat(explicitDependencies(first)).contains(dependency.singleCompileJar());

        Dep second = ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 4"));

        assertThat(compiledSources()).containsExactly("A.kt");
        assertThat(explicitDependencies(second)).contains(dependency.singleCompileJar());
    }

    private static List<String> explicitDependencies(Dep dep) {
        return jdeps(dep).getDependencyList().stream()
                .filter(d -> d.getKind() == Dependency.Kind.EXPLICIT)
                .map(Dependency::getPath)
                .collect(Collectors.toList());
    }

    @Test
    public void testCompilationErrorKeepsThePreviousState() {
        compileABC(false);

        assertThrows(
                CompilationStatusException.class,
                () -> ctx.rerunCompileTask(c -> c.updateSource("B.kt", "package ic", "fun b(): Int = a() +")));

        ctx.rerunCompileTask(c -> c.updateSource("B.kt", "package ic", "fun b(): Int = a() + 2"));

        assertThat(ctx.outLines()).doesNotContain("|  source changes unknown");
        assertThat(compiledSources()).containsExactly("B.kt");
        ctx.assertFilesExist(DirectoryType.CLASSES, "ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    @Test
    public void testConfigurationChangeCompilesFromScratch() {
        compileABC(false);

        ctx.rerunCompileTask(c -> c.addPassthroughFlags("-Xno-call-assertions"));

        assertThat(ctx.outLines())
                .contains("|  the compilation configuration changed, compiling from scratch");
        ctx.assertFilesExist(DirectoryType.CLASSES, "ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }
}
