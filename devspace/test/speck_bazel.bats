#!/usr/bin/env bats
#
# Tests for devspace/speck_bazel.sh. A stub `bazel` on PATH records its argv and
# exits with $STUB_BAZEL_STATUS, so these run in seconds and never start Bazel.
#
# Run with: devspace/speck_bazel.sh bats

setup() {
  script="${BATS_TEST_DIRNAME}/../speck_bazel.sh"
  export XDG_CACHE_HOME="${BATS_TEST_TMPDIR}/cache"
  stub_dir="${BATS_TEST_TMPDIR}/bin"
  mkdir -p "$stub_dir"
  cat >"${stub_dir}/bazel" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"${BATS_TEST_TMPDIR}/bazel_calls"
exit "\${STUB_BAZEL_STATUS:-0}"
EOF
  chmod +x "${stub_dir}/bazel"
  export PATH="${stub_dir}:${PATH}"
  export STUB_BAZEL_STATUS=0
}

failures() {
  cat "${XDG_CACHE_HOME}/speck_bazel/consecutive_failures"
}

@test "no arguments prints usage and fails" {
  run "$script"
  [ "$status" -eq 1 ]
  [[ "$output" == *"Usage: devspace/speck_bazel.sh"* ]]
}

@test "test passes explicit labels and adds --disk_cache=" {
  run "$script" test //:all_tests --test_output=errors
  [ "$status" -eq 0 ]
  grep -qx 'test --disk_cache= //:all_tests --test_output=errors' "${BATS_TEST_TMPDIR}/bazel_calls"
}

@test "query does not get --disk_cache=" {
  run "$script" query 'tests(//src/test/kotlin/io/bazel/worker:all)'
  [ "$status" -eq 0 ]
  grep -qx 'query tests(//src/test/kotlin/io/bazel/worker:all)' "${BATS_TEST_TMPDIR}/bazel_calls"
}

@test "wildcard labels are refused without calling bazel" {
  for label in //... //src/...:all //kotlin:all '//kotlin:*'; do
    run "$script" build "$label"
    [ "$status" -eq 1 ]
    [[ "$output" == *"wildcard"* ]]
  done
  [ ! -e "${BATS_TEST_TMPDIR}/bazel_calls" ]
}

@test "query with ... is refused" {
  run "$script" query 'deps(//...)'
  [ "$status" -eq 1 ]
  [ ! -e "${BATS_TEST_TMPDIR}/bazel_calls" ]
}

@test "flags outside the allowlist are refused" {
  run "$script" test //:all_tests --config=rbe
  [ "$status" -eq 1 ]
  [[ "$output" == *"not allowed"* ]]
  run "$script" build //:all_tests --test_filter=Foo
  [ "$status" -eq 1 ]
}

@test "more than five labels is refused" {
  run "$script" build //a:a //b:b //c:c //d:d //e:e //f:f
  [ "$status" -eq 1 ]
  [[ "$output" == *"more than 5 labels"* ]]
}

@test "run accepts a ktlint_fix target and refuses others" {
  run "$script" run //src/main/kotlin/io/bazel/worker:worker_ktlint_fix
  [ "$status" -eq 0 ]
  run "$script" run //src/main/kotlin/io/bazel/worker:worker
  [ "$status" -eq 1 ]
  [[ "$output" == *"not a run target allowed here"* ]]
}

@test "other bazel commands are refused" {
  run "$script" clean
  [ "$status" -eq 1 ]
  [[ "$output" == *"not allowed on a pod"* ]]
}

@test "two failures in a row trip the breaker, and refusals don't count" {
  export STUB_BAZEL_STATUS=3
  run "$script" test //:all_tests
  [ "$status" -eq 3 ]
  [ "$(failures)" -eq 1 ]
  run "$script" build //...
  [ "$status" -eq 1 ]
  [ "$(failures)" -eq 1 ]
  run "$script" test //:all_tests
  [ "$status" -eq 3 ]
  [ "$(failures)" -eq 2 ]
  export STUB_BAZEL_STATUS=0
  run "$script" test //:all_tests
  [ "$status" -eq 1 ]
  [[ "$output" == *"so this one is refused"* ]]
}

@test "--user-approved-retry resets the breaker, and a pass clears it" {
  mkdir -p "${XDG_CACHE_HOME}/speck_bazel"
  echo 2 >"${XDG_CACHE_HOME}/speck_bazel/consecutive_failures"
  run "$script" --user-approved-retry test //:all_tests
  [ "$status" -eq 0 ]
  [ "$(failures)" -eq 0 ]
}

@test "test //src/... (upstream CI's unit pattern) is allowed, build //src/... is not" {
  run "$script" test //src/... --test_output=errors
  [ "$status" -eq 0 ]
  grep -qx 'test --disk_cache= //src/... --test_output=errors' "${BATS_TEST_TMPDIR}/bazel_calls"
  run "$script" build //src/...
  [ "$status" -eq 1 ]
  [[ "$output" == *"allowed only with test"* ]]
}

@test "an approved retry that is then refused still resets the breaker" {
  mkdir -p "${XDG_CACHE_HOME}/speck_bazel"
  echo 2 >"${XDG_CACHE_HOME}/speck_bazel/consecutive_failures"
  run "$script" --user-approved-retry test //...
  [ "$status" -eq 1 ]
  [ "$(failures)" -eq 0 ]
}

@test "a passing query does not clear the breaker" {
  mkdir -p "${XDG_CACHE_HOME}/speck_bazel"
  echo 1 >"${XDG_CACHE_HOME}/speck_bazel/consecutive_failures"
  run "$script" query '//kotlin:all'
  [ "$status" -eq 0 ]
  [ "$(failures)" -eq 1 ]
}

@test "archive-integrity wants a full commit SHA" {
  run "$script" archive-integrity main
  [ "$status" -eq 1 ]
  [[ "$output" == *"40-character commit SHA"* ]]
}

@test "subcommands without arguments refuse extra arguments" {
  run "$script" ktlint-check //src/...
  [ "$status" -eq 1 ]
  [[ "$output" == *"takes no arguments"* ]]
}
