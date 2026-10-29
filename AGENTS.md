# rules_kotlin (tinyspeck fork)

Slack's fork of [bazel-contrib/rules_kotlin](https://github.com/bazel-contrib/rules_kotlin):
the Bazel rules for compiling Kotlin (`kt_jvm_library`, `kt_jvm_test`,
`kt_android_*`, ktlint, KSP/kapt) plus the Kotlin compile worker they call.
This file is the shared guide for any coding agent; `CLAUDE.md` points here and
`SPECK.md` adds what is specific to Speck on a DevSpace Pod.

## Branches

- `master` is the fork's default branch and tracks upstream `master`, with no
  Slack code changes.
- `slack/v<kotlin version>-rebase` branches carry Slack's patches: upstream
  plus Slack's cherry-picked commits (extra kotlinc options, the `ksp_opts`
  attribute, KSP2 fixes), re-cut onto upstream on each sync. slack-android-ng
  pins one commit of one of them with `archive_override` in its `MODULE.bazel`
  (`RULES_KOTLIN_SHA`). Most work targets the newest such branch. A re-cut
  branch gets a new name rather than a force-push, because the commit the
  consumer pins has to stay reachable for its archive URL to keep working.
- Keep Slack commits small and self-contained so each cherry-picks onto the
  next upstream sync, and say in the PR when a change is fork-only or could go
  upstream. The agent and DevSpace files (`devspaces.yaml`, `speck.yaml`,
  `SPECK.md`, `AGENTS.md`, `CLAUDE.md`, `devspace/`, `.claude/`, `.buddy/`) are
  fork-only and never go upstream.

## Layout

- `kotlin/`: public Starlark API (`jvm.bzl`, `android.bzl`, `lint.bzl`,
  `core.bzl`). Under `kotlin/internal/jvm/`, each rule has its own file
  (`kt_jvm_library.bzl`, ...), shared attributes are in `attrs.bzl`, and the
  compile and KSP actions are in `compile.bzl`.
- `src/main/kotlin/io/bazel/kotlin/`: the builder, meaning the persistent worker
  (`io/bazel/worker`), compile tasks, the KSP2 worker (`ksp2/`), jdeps and other
  compiler plugins.
- `src/main/starlark/`: release packaging, the compile pipeline
  (`core/pipeline`) and versioned repository setup. Pinned tool versions
  (Kotlin compiler, KSP, ktlint, ...) live in
  `src/main/starlark/core/repositories/versions.bzl`.
- `src/test/`: Kotlin unit and assertion tests, and Starlark tests.
- `examples/`: standalone example workspaces, run by CI as integration tests.
- `docs/kotlin.md`: generated from the Starlark docstrings; never hand-edit.
- `devspace/`: the DevSpace Pod image, its Gondola pipeline, and
  `speck_bazel.sh` (the only command Speck runs on a pod) with its BATS tests.

## Build and test

Bazel is pinned in `.bazelversion`; use bazelisk. `master` and the Slack
branches share upstream's targets:

| Task | Command |
| --- | --- |
| Unit tests (full, as upstream CI) | `bazel test //src/...` |
| Kotlin suites only | `bazel test //:all_tests` (no Starlark analysis tests) |
| ktlint check | `bazel test //... --test_tag_filters=ktlint --build_tests_only` |
| ktlint fix | `bazel run //:ktlint_fix_all` |
| Docs regen | `bazel run //docs:write_docs` |
| Docs check | `bazel test //docs/...` |
| Buildifier | `bazel run //:buildifier.check` / `//:buildifier.fix` |

On a pod, `devspace/speck_bazel.sh` wraps all of these;
`devspace/speck_bazel.sh preflight` runs the CI-aligned set.

## Validation

This fork has no PR CI: `.github/workflows` runs only on release tags, and
`.bazelci/` runs on upstream's BazelCI, not on fork PRs. A local run is the only
check a PR here gets before review, so run what matches the change and list what
ran in the PR, as evidence a reviewer can weigh:

| Changed | Run |
| --- | --- |
| Builder Kotlin (`src/main/kotlin/`) | the package's tests under `src/test/kotlin/`, then ktlint check |
| Rules / Starlark (`kotlin/`, `src/main/starlark/`) | the matching assertion and Starlark tests, the docs check, buildifier check |
| Public rule attributes or docstrings | docs regen, then the docs check |
| Tool versions (`versions.bzl`, `MODULE.bazel`) | `//src/...` |
| `devspace/speck_bazel.sh` | shellcheck and `bats devspace/test` |
| Anything before review | `//src/...` + ktlint + docs + buildifier (the `preflight` set) |

Not runnable locally: the `//examples:all` integration tests (RBE, Android
NDK), the macOS/Windows matrix, and the release workflow. Say so in the PR
rather than implying they passed. The real consumer check is slack-android-ng's
Bazel CI after bumping its pin (see `.claude/skills/bump-slack-android`).

## Conventions

- Starlark is formatted with buildifier (warnings list in
  `.bazelci/presubmit.yml`, mirrored in `.pre-commit-config.yaml`; it includes
  `+unsorted-dict-items`, so keep attribute dicts sorted).
- Kotlin follows ktlint with the root `.editorconfig`.
- New Starlark goes under `src/main/starlark/` as described in
  `CONTRIBUTING.md` (`core` or a feature directory).
- Any change to a public rule's attributes or docstrings needs a docs regen;
  the docs check fails otherwise.
- Add tests with behavior changes: builder changes in
  `src/test/kotlin/io/bazel/kotlin/builder`, rule changes as assertion tests in
  `src/test/kotlin/io/bazel/kotlin` or Starlark tests in `src/test/starlark`.
- Shell scripts pass shellcheck and are formatted with `shfmt -i 2`.

## DevSpaces

- A pod reads `devspaces.yaml` at the commit of the branch it attaches to.
  Reprovision after changing it; a running pod keeps its old config.
- `speck.yaml` and `SPECK.md` are read from the default branch, `master`, so
  new allowlist entries only take effect after they merge there.
- The pod image is built from `devspace/Dockerfile` by the Gondola pipeline in
  `devspace/gondola.yaml`. Bump its `BAZEL_VERSION` with `.bazelversion`.
