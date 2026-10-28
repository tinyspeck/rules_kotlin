---
name: bump-tool-version
description: Bump the Kotlin compiler, KSP, ktlint or another pinned tool in rules_kotlin. Use when asked to upgrade Kotlin or any tool pinned in src/main/starlark/core/repositories/versions.bzl.
---

# Bump a pinned tool version

Every downloaded tool is a `version(...)` entry in
`src/main/starlark/core/repositories/versions.bzl`, with `version`,
`url_templates` and `sha256`. `KOTLIN_CURRENT_COMPILER_RELEASE` is the Kotlin
compiler; the KSP entry's version is `<kotlin>-<ksp>` and must match the
compiler.

1. Update `version` and `sha256` for the entry. Get the sha256 from the
   release page's published checksum, not from a guess. If you can't fetch it,
   say so and leave the PR as a draft for a person to fill in.
2. Bumping Kotlin's major or minor version: check whether
   `src/main/starlark/` needs a new `rkt_<major>_<minor>` directory and whether
   `kotlin/internal/opts.bzl` options changed (see `CONTRIBUTING.md`,
   "Versioning"). Bump KSP to its release for the new compiler at the same
   time.
3. Maven-resolved deps are pinned in `kotlin_rules_maven_install.json`; when
   `MODULE.bazel`'s maven artifacts change, repin with
   `REPIN=1 bazel run @kotlin_rules_maven//:pin` (not runnable through
   `scripts/speck_bazel.sh`; ask the person to run it).
4. Verify: `scripts/speck_bazel.sh test //:all_tests`, then the docs check if
   any docstrings mention the version.
5. Add a line to `CHANGELOG.md`.
