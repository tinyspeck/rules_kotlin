---
name: bump-tool-version
description: Bump a pinned tool in rules_kotlin (Kotlin compiler, KSP, ktlint, Bazel, protobuf), including the files that must move with it.
---

# Bump a pinned tool version

## Where versions live

- Kotlin compiler, KSP, ktlint and other downloaded tools:
  `src/main/starlark/core/repositories/versions.bzl` (version, URL and
  `sha256` for each).
- Bazel: `.bazelversion`. Also bump `BAZEL_VERSION` in `devspace/Dockerfile`
  so pods don't re-download it.
- protobuf: `proto_version` in `MODULE.bazel`. Also bump `PROTOC_VERSION` in
  `devspace/Dockerfile`.
- Maven artifacts: `MODULE.bazel` (`maven.install`) with lock files
  `kotlin_rules_maven_install.json` and, on `slack/v2.4.20-rebase`,
  `kotlin_rules_maven_test_install.json`.

## Steps

1. Change the version and its `sha256` together. Take the checksum from the
   release's published checksum, not from a guess.
2. For Maven changes, repin: `REPIN=1 bazel run @unpinned_kotlin_rules_maven//:pin`
   (or `@unpinned_kotlin_rules_maven_test//:pin`). `devspace/speck_bazel.sh`
   can't run this; ask the person to repin, or say in the PR that the lock file
   needs it.
3. On a pod, run `devspace/speck_bazel.sh preflight` (with `exec_background`).
4. Kotlin or KSP bumps also need to match slack-android-ng, which pins the same
   versions through `rules_kotlin_extensions`. Mention the matching change in
   the PR.
