# Speck in rules_kotlin

This file is appended to Speck's instructions, read from `master`. It covers
what is specific to Speck on a DevSpace Pod; read `AGENTS.md` (and `CLAUDE.md`,
which points to it) for the repo itself.

## Which branch

Slack Android consumes a `slack/v<kotlin version>-rebase` branch, not
`master`, which only tracks upstream. Unless the person says otherwise, base
work on the newest of those branches and open PRs against it; to see which
commit Slack Android pins, read `RULES_KOTLIN_SHA` in slack-android-ng's
`MODULE.bazel`. Never force-push a `slack/` branch: the pinned commit has to
stay reachable. A pod on a branch that lacks `devspaces.yaml` and
`devspace/speck_bazel.sh` has no toolchain and no allowed commands; say so
rather than working around it.

## The pod is the verifier

This fork has no PR CI. Unlike slack-android-ng, opening a draft PR reaches no
checks, so do not wait on CI that will never report. Before calling a change
done, run the checks from the Validation table in `AGENTS.md`, and
`devspace/speck_bazel.sh preflight` before asking for review. In the PR, list
what ran and what could not (integration tests, RBE, macOS/Windows), as
evidence, not as a claim that the change is safe.

## What runs on the pod

Only `devspace/speck_bazel.sh`, in the `devspace` container. Call it exactly
as `devspace/speck_bazel.sh ...`, with no `./` prefix: the allowlist matches the
command literally. Run it with no arguments for its usage. The main commands:

- `test <label> ... [--test_filter=...] [--test_output=errors]`, `build`, `query`
- `preflight`: `test //src/...`, ktlint, the docs check, and buildifier (on PATH or through `//:buildifier.check`)
- `ktlint-check`, `ktlint-fix`, `docs-check`, `docs-regen`, `buildifier-check`, `buildifier-fix`
- `shellcheck`, `bats`: for changes to the script itself
- `archive-integrity <sha>`: the slack-android-ng pin for a pushed commit

Labels must be explicit (no `//...`, `:all`, `*`), except `test //src/...`, the
full unit suite upstream CI runs. `//:all_tests` is only the Kotlin suites
(assertion, builder, worker, convert, resource_strip_prefix); it leaves out the
Starlark analysis tests under `src/test/starlark` (including the KSP and
compile-pipeline ones), so for Starlark or rule changes run those packages'
suites or `test //src/...`. You do not need the script for searching: `rg`,
`fd` and file tools work from the sidecar, and git goes through the git tools.

## Scope and time

Run the narrowest target that covers the change first, then widen. `exec`
stops a command after 530 seconds; the first build on a fresh pod downloads
Bazel, the Kotlin compiler and Maven artifacts and can take longer, so run
`preflight`, `test //src/...` and any first build with `exec_background`.

## When it fails

After two failures in a row the script refuses to run. Read the failure once.
If the fix is clear, make it and run again; if not, stop and report the output
and what you think it means. Retry with `--user-approved-retry` first only when
the person says to. Never delete caches or the Bazel output base to free space;
report the disk error instead. If a download from Maven Central or GitHub
fails, report it; do not change repository URLs to get around it.

Some failures are the image, not your change: until pods move to the
rules_kotlin image (see `devspaces.yaml`), there is no C++ compiler, buildifier
binary or BATS on the pod, so targets that compile C++ and the `bats`
subcommand fail (buildifier falls back to `//:buildifier.check`).
Say which it was.

## Verifying in Slack Android

The consumer check for a rules_kotlin change is slack-android-ng's Bazel CI
with its pin moved to the new commit. Do it only when the person asks, since it
is a PR in another repo:

1. Push the commit to the fork branch.
2. Run `devspace/speck_bazel.sh archive-integrity <full sha>`, which prints the
   `RULES_KOTLIN_SHA` and `archive_override` block.
3. Follow `.claude/skills/bump-slack-android/SKILL.md` to open a draft PR in
   tinyspeck/slack-android-ng, in a pod for that repo.

## Pull requests

There is no PR template. Say what changed and why, whether it is fork-only or
upstreamable, and what ran on the pod. Open PRs as drafts and leave them as
drafts; mark one ready only when the person asks.
