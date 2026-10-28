---
name: ktlint
description: Check and fix Kotlin formatting in the rules_kotlin builder sources with ktlint. Use after editing any .kt file under src/main/kotlin, or when the KtLint CI task fails.
---

# ktlint for rules_kotlin

The builder's Kotlin sources each have generated `<name>_ktlint_test` and
`<name>_ktlint_fix` targets (the `kt_bootstrap_library` macro in
`src/main/kotlin/bootstrap.bzl`),
configured by the root `.editorconfig` through `//:ktlint_editorconfig`.

1. Check: `make ktlint.check`, or on a Speck pod
   `scripts/speck_bazel.sh ktlint-check`.
2. If it reports violations, fix them all at once: `make ktlint.fix`, or on a
   pod `scripts/speck_bazel.sh ktlint-fix`. To fix one package, run its target,
   e.g. `scripts/speck_bazel.sh run //src/main/kotlin/io/bazel/worker:worker_ktlint_fix`.
3. Review the diff (`git diff`): ktlint can reorder imports and rewrap lines
   outside the code you touched. Keep only changes to files you edited unless
   asked to clean up more.
4. Rerun the check until it passes.

A new Kotlin package under `src/main/kotlin` that has ktlint targets needs its
`*_ktlint_fix` target added to both the Makefile's `ktlint.fix` and the
`ktlint_fix_targets` list in `scripts/speck_bazel.sh`.
