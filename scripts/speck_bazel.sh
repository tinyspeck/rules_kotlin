#!/usr/bin/env bash
set -euo pipefail

# The Bazel commands Speck may run on a DevSpace Pod.
#
# speck.yaml allows this script and not `bazel` itself, so every Bazel call a
# Speck session makes passes these checks first:
#
# - Targets must be explicit labels. No `//...`, `:all`, or `*` wildcards, so a
#   call cannot turn into a whole-repo build by accident. Use the //:all_tests
#   and //:all_local_tests suites for the full unit test run.
# - Flags are limited to --test_filter and --test_output, since others change
#   what builds (--config, --define, --override_repository) or how much
#   (--jobs, --keep_going).
# - `run` is limited to the *_ktlint_fix targets under //src/main/kotlin.
# - The Makefile's ktlint.check, ktlint.fix and docs.regen targets are
#   subcommands here, so Speck can run them without a `make` entry.
# - After two failures in a row it refuses to run until the person says to
#   (--user-approved-retry), so a failure ends with a question, not a loop.
#
# The checks bound disk and time, not what code runs: this script and every
# BUILD and .bzl file Bazel evaluates live in the checkout Speck can edit.

max_targets=5
max_consecutive_failures=2

usage() {
  echo "Usage: $0 [--user-approved-retry] <command> ..."
  echo
  echo "Commands:"
  echo "  query <expression>"
  echo "  build <label> [<label> ...]                  (up to ${max_targets} labels)"
  echo "  test  <label> [<label> ...] [--test_filter=<filter>] [--test_output=errors|summary|all]"
  echo "  run   //src/main/kotlin/<pkg>:<name>_ktlint_fix"
  echo "  ktlint-check                                 (make ktlint.check)"
  echo "  ktlint-fix                                   (make ktlint.fix)"
  echo "  docs-regen                                   (make docs.regen)"
  echo
  echo "Example usage:"
  echo "  $0 test //:all_tests"
  echo "  $0 test //src/test/kotlin/io/bazel/kotlin/builder:builder_tests --test_output=errors"
  echo "  $0 query 'tests(//src/test/kotlin/io/bazel/worker:all)'"
  echo "  $0 build //kotlin:stardoc"
  echo
  echo "Refused: //..., :all and * wildcards, other bazel commands (clean,"
  echo "shutdown, ...), other run targets, and other flags."
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

# Labels are relative to the workspace root.
cd "$(dirname "$0")/.."

failure_file="${XDG_CACHE_HOME:-$HOME/.cache}/speck_bazel/consecutive_failures"
mkdir -p "$(dirname "$failure_file")"
failures=0
if [[ -f "$failure_file" ]]; then
  failures="$(cat "$failure_file")"
  [[ "$failures" =~ ^[0-9]+$ ]] || failures=0
fi

if [[ "$approved_retry" == true ]]; then
  failures=0
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
    echo "ERROR: '$label' is a wildcard. Name the targets, or use //:all_tests." >&2
    exit 1
  fi
}

# Runs one bazel invocation and updates the failure breaker. Counts the call as
# a failure before it runs, so one killed by a timeout or SIGTERM still counts.
# Only a passing build/test/run clears the count; a passing query leaves it as it
# was, so cheap queries can't reset the breaker.
run_bazel() {
  local command="$1"
  echo $((failures + 1)) >"$failure_file"
  echo "==> bazel $*"
  local status=0
  bazel "$@" || status=$?
  if [[ "$status" -eq 0 ]]; then
    if [[ "$command" != query ]]; then
      failures=0
    fi
    echo "$failures" >"$failure_file"
    return 0
  fi
  failures=$((failures + 1))
  echo "$failures" >"$failure_file"
  echo >&2
  echo "speck_bazel.sh: bazel exited ${status} (failure ${failures} of ${max_consecutive_failures} in a row)." >&2
  echo "Do not loop on this. Read the error once; if the fix is not obvious," >&2
  echo "report this output to the person you are working with and ask how they" >&2
  echo "want to proceed." >&2
  exit "$status"
}

# The *_ktlint_fix targets the Makefile's ktlint.fix runs, and the only ones
# `run` accepts. Keep in sync with the Makefile.
ktlint_fix_targets=(
  "//src/main/kotlin/io/bazel/kotlin/builder/cmd:build_lib_ktlint_fix"
  "//src/main/kotlin/io/bazel/kotlin/builder/cmd:merge_jdeps_lib_ktlint_fix"
  "//src/main/kotlin/io/bazel/kotlin/builder/tasks:tasks_ktlint_fix"
  "//src/main/kotlin/io/bazel/kotlin/builder/toolchain:toolchain_ktlint_fix"
  "//src/main/kotlin/io/bazel/kotlin/builder/utils:utils_ktlint_fix"
  "//src/main/kotlin/io/bazel/kotlin/builder/utils/jars:jars_ktlint_fix"
  "//src/main/kotlin/io/bazel/kotlin/compiler:compiler_ktlint_fix"
  "//src/main/kotlin/io/bazel/kotlin/plugin:skip-code-gen-lib_ktlint_fix"
  "//src/main/kotlin/io/bazel/kotlin/plugin/jdeps:jdeps-gen-lib_ktlint_fix"
  "//src/main/kotlin/io/bazel/worker:worker_ktlint_fix"
)

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
  run_bazel query "$1"
  ;;
build | test)
  bazel_args=("$command")
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
  run_bazel "${bazel_args[@]}"
  ;;
run)
  if [[ $# -ne 1 ]]; then
    echo "ERROR: run takes exactly one *_ktlint_fix target and no arguments." >&2
    exit 1
  fi
  allowed=false
  for candidate in "${ktlint_fix_targets[@]}"; do
    if [[ "$1" == "$candidate" ]]; then
      allowed=true
    fi
  done
  if [[ "$allowed" != true ]]; then
    echo "ERROR: '$1' is not a run target allowed here. Allowed:" >&2
    printf '  %s\n' "${ktlint_fix_targets[@]}" >&2
    exit 1
  fi
  run_bazel run "$1"
  ;;
ktlint-check)
  # Same as `make ktlint.check`. The tag filter keeps the `//...` pattern to
  # the ktlint tests, which are cheap.
  run_bazel test //... --test_tag_filters=ktlint --test_output=errors
  ;;
ktlint-fix)
  for target in "${ktlint_fix_targets[@]}"; do
    run_bazel run "$target"
  done
  ;;
docs-regen)
  # Same as `make docs.regen`: rebuild the stardoc output and copy it over the
  # checked-in docs/kotlin.md, which //docs:are_docs_up_to_date_test compares.
  run_bazel build //kotlin:stardoc
  cp bazel-bin/kotlin/kotlin.md docs/kotlin.md
  chmod u+w docs/kotlin.md
  echo "==> updated docs/kotlin.md"
  ;;
*)
  echo "ERROR: bazel '$command' is not allowed on a pod." >&2
  echo >&2
  usage >&2
  exit 1
  ;;
esac
