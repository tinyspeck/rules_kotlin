#!/usr/bin/env bash
set -euo pipefail

# The commands Speck may run on a DevSpace Pod.
#
# speck.yaml allows this script and not `bazel` itself, so every call a Speck
# session makes passes these checks first. Bazel is this repo's build, so unlike
# slack-android-ng's speck_bazel.sh there is no --user-requested gate. Instead:
#
# - Targets must be explicit labels. No `//...`, `:all`, or `*` wildcards, so a
#   call cannot turn into a whole-repo build by accident. The one exception is
#   `test //src/...`, the unit-test pattern upstream CI (.bazelci/tests.yml)
#   runs: //:all_tests leaves out the Starlark analysis tests under
#   src/test/starlark, so it is not the full suite. `preflight` runs //src/....
# - Flags are limited to --test_filter and --test_output, since others change
#   what builds (--config, --define, --override_repository) or how much
#   (--jobs, --keep_going).
# - `run` is limited to the formatters and doc generators the repo checks in.
# - Builds, tests and runs pass --disk_cache= and refuse to start with less than
#   10 GiB free: the two disk guards slack-android-ng's pods needed
#   (tinyspeck/slack-android-ng#129563).
# - After two failures in a row it refuses to run until the person says to
#   (--user-approved-retry), so a failure ends with a question, not a loop.
#   Its own refusals are not failures and don't count.
#
# master tracks upstream bazel-contrib/rules_kotlin and each slack/*-rebase
# branch is upstream plus Slack's commits, so both share upstream's lint and
# docs targets (//:ktlint_fix_all, //docs:write_docs, //:buildifier.*).
#
# The checks bound disk and time, not what code runs: this script and every
# BUILD and .bzl file Bazel evaluates live in the checkout Speck can edit.

max_targets=5
max_consecutive_failures=2
min_free_gb=10

usage() {
  cat <<EOF
Usage: devspace/speck_bazel.sh [--user-approved-retry] <command> ...

Commands:
  query <expression>                one quoted expression, no '...'
  build <label> [<label> ...]       up to ${max_targets} explicit labels
  test  <label> [<label> ...] [--test_filter=<filter>] [--test_output=errors|summary|all]
  run   <target>                    a formatter or doc generator, listed below
  preflight                         test //src/..., ktlint-check, docs-check, buildifier-check
  ktlint-check | ktlint-fix         ktlint over the builder sources
  docs-check   | docs-regen         docs/kotlin.md against the Starlark docstrings
  buildifier-check | buildifier-fix Starlark formatting and lint
  shellcheck | bats                 lint and test this script (devspace/)
  archive-integrity <commit-sha>    the archive_override pin for slack-android-ng

Run targets: //src/main/kotlin/<pkg>:<name>_ktlint_fix, //:ktlint_fix_all,
//docs:write_docs, //:buildifier.check and //:buildifier.fix.

Examples:
  devspace/speck_bazel.sh test //src/test/kotlin/io/bazel/kotlin/builder:builder_tests --test_output=errors
  devspace/speck_bazel.sh query 'tests(//src/test/kotlin/io/bazel/worker:all)'
  devspace/speck_bazel.sh preflight

Refused: //..., :all and * wildcards in build and test (except test //src/...,
the full unit suite upstream CI runs), other bazel commands
(clean, shutdown, ...), other run targets, and other flags.
EOF
}

if [[ $# -lt 1 ]] || [[ "$1" == "-h" ]] || [[ "$1" == "--help" ]]; then
  usage
  exit 1
fi

approved_retry=false
if [[ "$1" == "--user-approved-retry" ]]; then
  approved_retry=true
  shift
fi

if [[ $# -lt 1 ]]; then
  echo "ERROR: no command given." >&2
  echo >&2
  usage >&2
  exit 1
fi

# Labels and paths below are relative to the workspace root.
cd "$(dirname "$0")/.."

failure_file="${XDG_CACHE_HOME:-$HOME/.cache}/speck_bazel/consecutive_failures"
mkdir -p "$(dirname "$failure_file")"
failures=0
if [[ -f "$failure_file" ]]; then
  failures="$(cat "$failure_file")"
  [[ "$failures" =~ ^[0-9]+$ ]] || failures=0
fi

if [[ "$approved_retry" == true ]]; then
  # Persist the reset now, so a call refused before any step runs (a bad label,
  # the disk guard) doesn't leave the breaker tripped for the next call.
  failures=0
  echo 0 >"$failure_file"
elif [[ "$failures" -ge "$max_consecutive_failures" ]]; then
  echo "ERROR: the last ${failures} speck_bazel.sh calls failed, so this one is refused." >&2
  echo "Stop and report those failures to the person you are working with. Run" >&2
  echo "again only if they tell you to, with --user-approved-retry first." >&2
  exit 1
fi

# A label is explicit when it names one package and target: no recursive
# `...`, no `:all`/`:*` or `*` wildcards.
check_label() {
  local label="$1"
  if [[ "$label" != //* ]] && [[ "$label" != @* ]]; then
    echo "ERROR: '$label' is not an absolute label. Expected something like //:all_tests." >&2
    exit 1
  fi
  if [[ "$label" == *...* ]] || [[ "$label" == *"*"* ]] || [[ "$label" == *:all ]] ||
    [[ "$label" == *:all-targets ]]; then
    echo "ERROR: '$label' is a wildcard. Name the targets, or use test //src/... for the full unit suite." >&2
    exit 1
  fi
}

# defines_target <BUILD file> <name regex>: whether the file declares the target.
defines_target() {
  [[ -f "$1" ]] && grep -Eq "name = \"$2\"" "$1"
}

# Refuses, before anything starts, when the disk holding Bazel's output base is
# nearly full; a build that fills it fails halfway and leaves the pod unusable.
check_free_disk() {
  local dir="$HOME/.cache/bazel"
  [[ -d "$dir" ]] || dir="$HOME"
  local free_kb
  free_kb="$(df -Pk "$dir" 2>/dev/null | awk 'NR == 2 { print $4 }')" || return 0
  [[ "$free_kb" =~ ^[0-9]+$ ]] || return 0
  local free_gb=$((free_kb / 1024 / 1024))
  if [[ "$free_gb" -lt "$min_free_gb" ]]; then
    echo "ERROR: only ${free_gb} GiB free on the disk holding ${dir}; at least ${min_free_gb} GiB is needed." >&2
    echo "Don't free space by deleting caches yourself. Report this to the person you are working with." >&2
    exit 1
  fi
}

# Runs one counted step and updates the failure breaker. Counts the step as a
# failure before it runs, so one killed by a timeout or SIGTERM still counts.
# Only a passing build, test, run or check clears the count; a passing query
# leaves it as it was, so cheap queries can't reset the breaker.
run_step() {
  local clears="$1"
  shift
  echo $((failures + 1)) >"$failure_file"
  echo "==> $*"
  local status=0
  "$@" || status=$?
  if [[ "$status" -eq 0 ]]; then
    if [[ "$clears" == true ]]; then
      failures=0
    fi
    echo "$failures" >"$failure_file"
    return 0
  fi
  failures=$((failures + 1))
  echo "$failures" >"$failure_file"
  echo >&2
  echo "speck_bazel.sh: '$1' exited ${status} (failure ${failures} of ${max_consecutive_failures} in a row)." >&2
  echo "Do not loop on this. Read the error once; if the fix is not obvious," >&2
  echo "report this output to the person you are working with and ask how they" >&2
  echo "want to proceed." >&2
  exit "$status"
}

# bazel_step <command> <args...>: one bazel invocation with the pod-wide flags.
# --disk_cache= turns off any disk cache a user.bazelrc enables, and
# --config=devspace applies once .bazelrc defines it.
bazel_step() {
  local command="$1"
  shift
  local args=("$command")
  local clears=false
  if [[ "$command" != query ]]; then
    check_free_disk
    args+=("--disk_cache=")
    clears=true
  fi
  if grep -Eq '^(common|build):devspace[[:space:]]' .bazelrc 2>/dev/null &&
    [[ "$command" != query ]]; then
    args+=("--config=devspace")
  fi
  run_step "$clears" bazel "${args[@]}" "$@"
}

allowed_run_target() {
  local target="$1"
  if [[ "$target" =~ ^//src/main/kotlin/[A-Za-z0-9_/.-]+:[A-Za-z0-9_.-]+_ktlint_fix$ ]]; then
    return 0
  fi
  case "$target" in
  //:ktlint_fix_all | //:buildifier.check | //:buildifier.fix | //docs:write_docs) return 0 ;;
  *) return 1 ;;
  esac
}

no_args() {
  if [[ $# -gt 0 ]]; then
    echo "ERROR: ${command} takes no arguments." >&2
    exit 1
  fi
}

ktlint_check() {
  # The KtLint task in .bazelci/presubmit.yml. --build_tests_only keeps the
  # `//...` pattern to the ktlint tests and their inputs instead of building
  # every target in the repo.
  bazel_step test //... --test_tag_filters=ktlint --build_tests_only --test_output=errors
}

ktlint_fix() {
  bazel_step run //:ktlint_fix_all
}

docs_check() {
  # write_source_files adds the diff tests the Stardoc task runs as //docs/...;
  # docs has no subpackages, so //docs:all is the same.
  bazel_step test //docs:all --test_output=errors
}

docs_regen() {
  bazel_step run //docs:write_docs
}

buildifier_step() {
  local mode="$1"
  if command -v buildifier >/dev/null 2>&1; then
    # The warnings list the buildifier task in .bazelci/presubmit.yml uses.
    local warnings
    warnings="$(sed -n 's/^[[:space:]]*warnings:[[:space:]]*"\(.*\)"[[:space:]]*$/\1/p' .bazelci/presubmit.yml 2>/dev/null | head -n 1)"
    local args=("-mode=${mode}")
    if [[ "$mode" == check ]]; then
      args+=("-lint=warn")
    else
      args+=("-lint=fix")
    fi
    if [[ -n "$warnings" ]]; then
      args+=("-warnings=${warnings}")
    fi
    run_step true buildifier "${args[@]}" -r .
  elif defines_target BUILD "buildifier\\.${mode}"; then
    bazel_step run "//:buildifier.${mode}"
  else
    echo "ERROR: no buildifier on this image and no //:buildifier.${mode} target on this branch." >&2
    echo "Match the surrounding Starlark style by hand and say in the PR that buildifier was not run." >&2
    exit 1
  fi
}

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "ERROR: $1 is not on this image. It ships in devspace/Dockerfile; see devspaces.yaml for when pods switch to it." >&2
    exit 1
  fi
}

archive_integrity() {
  local sha="$1"
  if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR: archive-integrity takes a full 40-character commit SHA pushed to tinyspeck/rules_kotlin." >&2
    exit 1
  fi
  local url="https://github.com/tinyspeck/rules_kotlin/archive/${sha}.zip"
  local tmp
  tmp="$(mktemp)"
  # shellcheck disable=SC2064 # expand $tmp now, while it is set
  trap "rm -f '$tmp'" EXIT
  echo "==> curl ${url}" >&2
  if ! curl -fsSL --retry 2 -o "$tmp" "$url"; then
    echo "ERROR: could not download ${url}. Is ${sha} pushed to tinyspeck/rules_kotlin?" >&2
    exit 1
  fi
  local integrity
  integrity="$(python3 -c 'import base64, hashlib, sys; print("sha256-" + base64.b64encode(hashlib.sha256(open(sys.argv[1], "rb").read()).digest()).decode())' "$tmp")"
  cat <<EOF
RULES_KOTLIN_SHA = "${sha}"

archive_override(
    module_name = "rules_kotlin",
    integrity = "${integrity}",
    strip_prefix = "rules_kotlin-{}".format(RULES_KOTLIN_SHA),
    urls = ["https://github.com/tinyspeck/rules_kotlin/archive/{}.zip".format(RULES_KOTLIN_SHA)],
)
EOF
}

command="$1"
shift

case "$command" in
query)
  if [[ $# -ne 1 ]]; then
    echo "ERROR: query takes exactly one expression, quoted." >&2
    exit 1
  fi
  # A recursive `...` pattern loads every package and fetches every external
  # repo; a leading `-` would reach bazel as an option. `:all` within one
  # package is cheap and stays allowed.
  if [[ "$1" == -* ]] || [[ "$1" == *...* ]]; then
    echo "ERROR: query expressions may not use '...' or start with '-'." >&2
    exit 1
  fi
  bazel_step query "$1"
  ;;
build | test)
  bazel_args=()
  label_count=0
  while [[ $# -gt 0 ]]; do
    arg="$1"
    shift
    case "$arg" in
    --test_filter=*)
      if [[ "$command" != test ]]; then
        echo "ERROR: --test_filter only applies to test." >&2
        exit 1
      fi
      bazel_args+=("$arg")
      ;;
    --test_output=errors | --test_output=summary | --test_output=all)
      bazel_args+=("$arg")
      ;;
    -*)
      echo "ERROR: option '$arg' is not allowed. Only --test_filter and --test_output are." >&2
      exit 1
      ;;
    //src/...)
      # Upstream CI's unit-test pattern; see the header.
      if [[ "$command" != test ]]; then
        echo "ERROR: //src/... is allowed only with test." >&2
        exit 1
      fi
      label_count=$((label_count + 1))
      bazel_args+=("$arg")
      ;;
    *)
      check_label "$arg"
      label_count=$((label_count + 1))
      if [[ "$label_count" -gt "$max_targets" ]]; then
        echo "ERROR: more than ${max_targets} labels in one call." >&2
        exit 1
      fi
      bazel_args+=("$arg")
      ;;
    esac
  done
  if [[ "$label_count" -eq 0 ]]; then
    echo "ERROR: $command needs at least one label." >&2
    exit 1
  fi
  bazel_step "$command" "${bazel_args[@]}"
  ;;
run)
  if [[ $# -ne 1 ]]; then
    echo "ERROR: run takes exactly one target and no arguments." >&2
    exit 1
  fi
  if ! allowed_run_target "$1"; then
    echo "ERROR: '$1' is not a run target allowed here. Allowed: //src/main/kotlin/<pkg>:<name>_ktlint_fix," >&2
    echo "//:ktlint_fix_all, //docs:write_docs, //:buildifier.check, //:buildifier.fix." >&2
    exit 1
  fi
  bazel_step run "$1"
  ;;
preflight)
  no_args "$@"
  # The share of .bazelci/presubmit.yml a pod can run. The fork has no PR CI,
  # so this is the gate before calling a change done.
  bazel_step test //src/... --test_output=errors
  ktlint_check
  docs_check
  not_run="//examples:all integration tests, RBE, and the macOS/Windows matrix"
  # buildifier_step uses buildifier on PATH, else //:buildifier.check, so
  # Starlark lint runs on any image, not only one with the binary.
  if command -v buildifier >/dev/null 2>&1 || defines_target BUILD 'buildifier\.check'; then
    buildifier_step check
    echo "==> preflight passed: //src/..., ktlint, the docs check and buildifier."
  else
    not_run="buildifier (not on this image), ${not_run}"
    echo "==> preflight passed: //src/..., ktlint and the docs check."
  fi
  echo "    Not run: ${not_run}."
  ;;
ktlint-check)
  no_args "$@"
  ktlint_check
  ;;
ktlint-fix)
  no_args "$@"
  ktlint_fix
  ;;
docs-check)
  no_args "$@"
  docs_check
  ;;
docs-regen)
  no_args "$@"
  docs_regen
  ;;
buildifier-check)
  no_args "$@"
  buildifier_step check
  ;;
buildifier-fix)
  no_args "$@"
  buildifier_step fix
  ;;
shellcheck)
  no_args "$@"
  require_tool shellcheck
  run_step true shellcheck devspace/speck_bazel.sh
  ;;
bats)
  no_args "$@"
  require_tool bats
  run_step true bats devspace/test
  ;;
archive-integrity)
  if [[ $# -ne 1 ]]; then
    echo "ERROR: archive-integrity takes exactly one commit SHA." >&2
    exit 1
  fi
  archive_integrity "$1"
  ;;
*)
  echo "ERROR: '$command' is not allowed on a pod." >&2
  echo >&2
  usage >&2
  exit 1
  ;;
esac
