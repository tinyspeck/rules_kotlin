# rules_kotlin (tinyspeck fork)

Slack's fork of [bazelbuild/rules_kotlin](https://github.com/bazelbuild/rules_kotlin):
the Bazel rules for compiling Kotlin (`kt_jvm_library`, `kt_jvm_test`,
`kt_android_*`, ktlint, KSP/kapt) plus the Kotlin compile worker they call.
Keep changes small and upstreamable where possible; note in the PR when a
change is fork-only.

## Layout

- `kotlin/` — public Starlark API (`jvm.bzl`, `lint.bzl`, `core.bzl`,
  `repositories.bzl`) and its implementation under `kotlin/internal/`.
- `src/main/kotlin/io/bazel/kotlin/` — the builder: the persistent worker
  (`io/bazel/worker`), compile tasks, jdeps and other compiler plugins.
- `src/main/starlark/` — release packaging and versioned repository setup.
  Pinned tool versions (Kotlin compiler, KSP, ktlint, ...) live in
  `src/main/starlark/core/repositories/versions.bzl`.
- `src/test/` — Kotlin unit/assertion tests and Starlark tests.
- `examples/` — standalone example workspaces, run by CI as integration tests.
- `docs/kotlin.md` — generated from the Starlark docstrings; never hand-edit.

## Build and test

Bazel version is pinned in `.bazelversion`; use bazelisk.

```sh
bazel test //:all_tests          # unit suite (what `make test` runs)
bazel test //:all_local_tests    # plus tests that only run locally
bazel test //src/test/kotlin/io/bazel/kotlin/builder:builder_tests
make ktlint.check                # ktlint over the builder sources
make ktlint.fix                  # apply ktlint fixes
make docs.regen                  # regenerate docs/kotlin.md
```

## Conventions

- Starlark is formatted with buildifier (warnings list in
  `.pre-commit-config.yaml`, kept in sync with `.bazelci/presubmit.yml`).
- Kotlin follows ktlint with the root `.editorconfig`.
- New Starlark goes under `src/main/starlark/` as described in
  `CONTRIBUTING.md` (`core`, `rkt_<version>`, or a feature directory).
- Any change to a public rule's attributes or docstrings needs
  `make docs.regen`; `//docs:are_docs_up_to_date_test` fails otherwise.
- Add tests with behavior changes: builder changes in
  `src/test/kotlin/io/bazel/kotlin/builder`, rule changes as assertion tests in
  `src/test/kotlin/io/bazel/kotlin`.
