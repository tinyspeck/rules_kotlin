# Working in rules_kotlin on a Speck DevSpace Pod

Read `CLAUDE.md` first for the repo layout and conventions. This file covers
what is different on a pod.

## Running Bazel

`bazel` is not on the allowlist. Every call goes through
`scripts/speck_bazel.sh` in the `devspace` container:

```sh
scripts/speck_bazel.sh test //:all_tests
scripts/speck_bazel.sh test //src/test/kotlin/io/bazel/kotlin/builder:builder_tests --test_output=errors
scripts/speck_bazel.sh test //src/test/kotlin/io/bazel/kotlin:KotlinJvmBasicAssertionTest
scripts/speck_bazel.sh query 'tests(//src/test/kotlin/io/bazel/worker:all)'
scripts/speck_bazel.sh ktlint-check
scripts/speck_bazel.sh ktlint-fix
scripts/speck_bazel.sh docs-regen
```

- Explicit labels only (no `//...` or `:all`); find labels with `query`.
- The first build fetches the pinned Bazel and the Kotlin compiler and
  maven deps, so it takes several minutes. Run full suites with
  `exec_background`, not `exec`.
- After two failures in a row the script refuses to run. Stop and report the
  failure; rerun with `--user-approved-retry` only when the person says to.
- Run the narrowest test target that covers the change first, then
  `//:all_tests` before opening a PR.

## What a pod can and can't verify

- **Can:** the unit suites (`//:all_tests`, `//:all_local_tests`), individual
  builder and assertion tests, ktlint, and the stardoc docs check
  (`scripts/speck_bazel.sh test //docs:are_docs_up_to_date_test`).
- **Can't:**
  - `examples/` integration tests. CI runs them as separate workspaces with
    `--config=rbe`, and the pod has no RBE credentials.
  - Android rules end to end. The pod has no Android SDK (`scripts/install_android_sdk`
    is not run), so `kt_android_*` changes are verified in CI only.
  - The `.bazelci/presubmit.yml` matrix (ubuntu2204/2404, buildifier) and the
    release archive (`scripts/release.sh`).

  Say which of these a change depends on in the PR description, rather than
  claiming it was tested.

## Formatting

There is no buildifier on the pod. Match the surrounding Starlark style by
hand (4-space indents, trailing commas, sorted `load` symbols) and let CI's
buildifier check confirm it. Kotlin formatting is checked with `ktlint-check`
and fixed with `ktlint-fix`.
