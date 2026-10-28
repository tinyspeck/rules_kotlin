# rules_kotlin (tinyspeck fork)

Slack's fork of [bazelbuild/rules_kotlin](https://github.com/bazelbuild/rules_kotlin):
the Bazel rules for compiling Kotlin (`kt_jvm_library`, `kt_jvm_test`,
`kt_android_*`, ktlint, KSP/kapt) plus the Kotlin compile worker they call.
This file is the shared guide for any coding agent; `CLAUDE.md` points here and
`SPECK.md` adds what is specific to Speck on a DevSpace Pod.

## Branches

- `slack/v2.4.20-rebase` is the branch Slack Android consumes: upstream
  v2.4.20 plus Slack's cherry-picked commits. slack-android-ng pins one commit
  of it with `archive_override` in its `MODULE.bazel` (`RULES_KOTLIN_SHA`).
  Most work targets this branch. It is re-cut on each upstream rebase, so every
  Slack commit on it must cherry-pick cleanly onto the next upstream tag.
- `master` is the fork's default branch. It is an older upstream snapshot
  (Makefile-driven, now on Bazel 9.3.1); Speck reads `speck.yaml` and `SPECK.md` from
  it, so the Speck setup lives there too.
- Keep changes small and upstreamable where possible, and say in the PR when a
  change is fork-only. The Speck/DevSpace files (`devspaces.yaml`,
  `speck.yaml`, `SPECK.md`, `AGENTS.md`, `CLAUDE.md`, `devspace/`, `.claude/`,
  `.buddy/`) are fork-only and never go upstream.

## Layout

- `kotlin/`: public Starlark API (`jvm.bzl`, `lint.bzl`, `core.bzl`,
  `repositories.bzl`) and its implementation under `kotlin/internal/`.
- `src/main/kotlin/io/bazel/kotlin/`: the builder, meaning the persistent worker
  (`io/bazel/worker`), compile tasks, jdeps and other compiler plugins.
- `src/main/starlark/`: release packaging and versioned repository setup.
  Pinned tool versions (Kotlin compiler, KSP, ktlint, ...) live in
  `src/main/starlark/core/repositories/versions.bzl`.
- `src/test/`: Kotlin unit and assertion tests, and Starlark tests.
- `examples/`: standalone example workspaces, run by CI as integration tests.
- `docs/kotlin.md`: generated from the Starlark docstrings; never hand-edit.
- `devspace/`: the DevSpace Pod image, its Gondola pipeline, and
  `speck_bazel.sh` (the only command Speck runs on a pod) with its BATS tests.

## Build and test

Bazel is pinned in `.bazelversion`; use bazelisk. The two branches differ:

| Task | `slack/v2.4.20-rebase` | `master` |
| --- | --- | --- |
| Bazel | 9.2.0 (moving to 9.3.1) | 9.3.1 |
| Unit tests | `bazel test //:all_tests` | same |
| ktlint check | `bazel test //... --test_tag_filters=ktlint --build_tests_only` | same, or `make ktlint.check` |
| ktlint fix | `bazel run //:ktlint_fix_all` | `make ktlint.fix` |
| Docs regen | `bazel run //docs:write_docs` | `make docs.regen` |
| Docs check | `bazel test //docs/...` | `bazel test //docs:are_docs_up_to_date_test` |
| Buildifier | `bazel run //:buildifier.check` / `.fix` | `buildifier` (pre-commit) |

On a pod, `devspace/speck_bazel.sh` wraps all of these and works on either
branch; `devspace/speck_bazel.sh preflight` runs the CI-aligned set.

## Validation

This fork has no PR CI: `.github/workflows` runs only on release tags, and
`.bazelci/` runs on upstream's BazelCI, not on fork PRs. A local run is the only
check a PR here gets before review, so run what matches the change and list what
ran in the PR, as evidence a reviewer can weigh:

| Changed | Run |
| --- | --- |
| Builder Kotlin (`src/main/kotlin/`) | the package's tests under `src/test/kotlin/`, then ktlint check |
| Rules / Starlark (`kotlin/`, `src/main/starlark/`) | the matching assertion tests, the docs check, buildifier check |
| Public rule attributes or docstrings | docs regen, then the docs check |
| Tool versions (`versions.bzl`, `MODULE.bazel`) | `//:all_tests` |
| `devspace/speck_bazel.sh` | shellcheck and `bats devspace/test` |
| Anything before review | `//:all_tests` + ktlint + docs (the `preflight` set) |

Not runnable locally: the `//examples:all` integration tests (RBE, Android
NDK), the macOS/Windows matrix, and the release workflow. Say so in the PR
rather than implying they passed. The real consumer check is slack-android-ng's
Bazel CI after bumping its pin (see `.claude/skills/bump-slack-android`).

## Conventions

- Starlark is formatted with buildifier (warnings list in
  `.bazelci/presubmit.yml`, mirrored in `.pre-commit-config.yaml`).
- Kotlin follows ktlint with the root `.editorconfig`.
- New Starlark goes under `src/main/starlark/` as described in
  `CONTRIBUTING.md` (`core`, `rkt_<version>`, or a feature directory).
- Any change to a public rule's attributes or docstrings needs a docs regen;
  the docs check fails otherwise.
- Add tests with behavior changes: builder changes in
  `src/test/kotlin/io/bazel/kotlin/builder`, rule changes as assertion tests in
  `src/test/kotlin/io/bazel/kotlin`.
- Shell scripts pass shellcheck and are formatted with `shfmt -i 2`.

## DevSpaces

- A pod reads `devspaces.yaml` at the commit of the branch it attaches to.
  Reprovision after changing it; a running pod keeps its old config.
- `speck.yaml` and `SPECK.md` are read from `master`, so new allowlist entries
  only take effect after they merge there.
- The pod image is built from `devspace/Dockerfile` by the Gondola pipeline in
  `devspace/gondola.yaml`. Bump its `BAZEL_VERSION` with `.bazelversion`.
