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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private static boolean compiledFromScratch() {
        return ctx.outLines().stream().anyMatch(line -> line.endsWith("compiling from scratch"));
    }

    private static Dep compileDependency() {
        return compileDependency("dep", "Dependency");
    }

    private static Dep compileDependency(String packageName, String className) {
        return ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.compileKotlin();
                    c.addSource(className + ".kt", "package " + packageName, "class " + className);
                    c.outputJar();
                });
    }

    /** Compiles the sources with the dependencies, incrementally or not, and returns the jdeps. */
    private static Dependencies compileWithDependencies(
            boolean incremental, Map<String, String[]> sources, Dep... dependencies) {
        Dep dep = ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    if (incremental) {
                        c.incrementalCompilation();
                    }
                    c.compileKotlin();
                    c.addDirectDependencies(dependencies);
                    sources.forEach(c::addSource);
                    c.outputJar();
                    c.outputJdeps();
                });
        return jdeps(dep);
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
        return dependencies(dep, Dependency.Kind.EXPLICIT);
    }

    private static List<String> dependencies(Dep dep, Dependency.Kind kind) {
        return dependencies(jdeps(dep), kind);
    }

    private static List<String> dependencies(Dependencies jdeps, Dependency.Kind kind) {
        return jdeps.getDependencyList().stream()
                .filter(d -> d.getKind() == kind)
                .map(Dependency::getPath)
                .collect(Collectors.toList());
    }

    @Test
    public void testJdepsOfAnIncrementalCompilationMatchAFullCompilation() {
        Dep first = compileDependency("first", "First");
        Dep second = compileDependency("second", "Second");
        Map<String, String[]> sources = new LinkedHashMap<>();
        sources.put("A.kt", new String[] {"package ic", "fun a() = first.First()"});
        sources.put("B.kt", new String[] {"package ic", "fun b() = second.Second()"});
        sources.put("C.kt", C);
        compileWithDependencies(true, sources, first, second);

        // A.kt stops using the first dependency; B.kt, which uses the second, is not recompiled.
        String[] a = {"package ic", "fun a(): Any = 1"};
        Dependencies incremental =
                jdeps(ctx.rerunCompileTask(c -> c.updateSource("A.kt", a)));
        assertThat(compiledSources()).containsExactly("A.kt");

        sources.put("A.kt", a);
        Dependencies full = compileWithDependencies(false, sources, first, second);
        assertThat(incremental.getDependencyList())
                .containsExactlyElementsIn(full.getDependencyList())
                .inOrder();
        assertThat(dependencies(incremental, Dependency.Kind.UNUSED))
                .containsExactly(first.singleCompileJar());
        assertThat(dependencies(incremental, Dependency.Kind.EXPLICIT))
                .contains(second.singleCompileJar());
    }

    @Test
    public void testRemovedSourceDropsItsDependencies() {
        Dep dependency = compileDependency();
        Map<String, String[]> sources = new LinkedHashMap<>();
        sources.put("A.kt", A);
        sources.put("UsesDependency.kt", new String[] {"package ic", "fun use() = dep.Dependency()"});
        compileWithDependencies(true, sources, dependency);

        Dependencies jdeps = jdeps(ctx.rerunCompileTask(c -> c.removeSource("UsesDependency.kt")));

        assertThat(compiledFromScratch()).isFalse();
        assertThat(dependencies(jdeps, Dependency.Kind.UNUSED))
                .containsExactly(dependency.singleCompileJar());
        assertThat(dependencies(jdeps, Dependency.Kind.EXPLICIT))
                .doesNotContain(dependency.singleCompileJar());
    }

    @Test
    public void testAddedDependencyKeepsTheState() {
        Dep dependency = compileDependency();
        compileABC(false);

        Dep dep = ctx.rerunCompileTask(c -> {
            c.addDirectDependencies(dependency);
            c.updateSource("A.kt", "package ic", "fun a(): Int = dep.Dependency().hashCode()");
        });

        assertThat(compiledFromScratch()).isFalse();
        assertThat(compiledSources()).containsExactly("A.kt");
        assertThat(explicitDependencies(dep)).contains(dependency.singleCompileJar());
    }

    @Test
    public void testDependencyChangesUpdateTheJdeps() {
        Dep used = compileDependency();
        Dep unused = compileDependency();
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.addDirectDependencies(used, unused);
                    c.addSource("A.kt", A);
                    c.addSource("UsesDependency.kt", "package ic", "fun use() = dep.Dependency()");
                    c.outputJar();
                    c.outputJdeps();
                });

        // Nothing recompiles: the jdeps plugin does not run.
        Dep dep = ctx.rerunCompileTask(c -> {
            c.removeDependencies(unused);
            c.makeTransitive(used);
        });

        assertThat(compiledFromScratch()).isFalse();
        assertThat(compiledSources()).isEmpty();
        assertThat(explicitDependencies(dep)).containsExactly(used.singleCompileJar());
        assertThat(dependencies(dep, Dependency.Kind.UNUSED)).isEmpty();
    }

    @Test
    public void testDependencyThatIsNoLongerDirectFailsStrictDeps() {
        Dep dependency = compileDependency();
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.kotlinStrictDeps("error");
                    c.addDirectDependencies(dependency);
                    c.addSource("A.kt", A);
                    c.addSource("UsesDependency.kt", "package ic", "fun use() = dep.Dependency()");
                    c.outputJar();
                    c.outputJdeps();
                });

        ctx.runFailingCompileTaskAndValidateOutput(
                () -> ctx.rerunCompileTask(c -> {
                    c.makeTransitive(dependency);
                    c.updateSource("A.kt", "package ic", "fun a(): Int = 5");
                }),
                lines -> {
                    assertThat(lines)
                            .contains("|  a direct dependency was removed, compiling from scratch");
                    assertThat(lines.stream().anyMatch(l -> l.contains("Strict Deps Violations")))
                            .isTrue();
                });
    }

    @Test
    public void testWithoutInputDigestsCompilesNonIncrementally() {
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.withoutInputDigests();
                    c.compileKotlin();
                    c.addSource("A.kt", A);
                    c.outputJar();
                });

        assertThat(ctx.outLines()).contains("incremental compilation unavailable:");
        ctx.assertFilesExist(DirectoryType.CLASSES, "ic/AKt.class");
    }

    @Test
    public void testRootResetBetweenRequestsDisablesIncrementalCompilation() throws IOException {
        KotlinJvmTestBuilder.restartWorker();
        try {
            compileABC(false);

            // A sandboxed worker's directory is reset before each request.
            try (Stream<Path> paths = Files.walk(KotlinJvmTestBuilder.incrementalCompilationRoot())) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
            ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 6"));
            assertThat(ctx.outLines()).contains("warning: incremental compilation disabled:");
            ctx.assertFilesExist(DirectoryType.CLASSES, "ic/AKt.class", "ic/BKt.class", "ic/C.class");

            ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 7"));
            assertThat(ctx.outLines()).contains("incremental compilation unavailable:");
            assertThat(ctx.outLines()).doesNotContain("warning: incremental compilation disabled:");
        } finally {
            KotlinJvmTestBuilder.restartWorker();
        }
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
