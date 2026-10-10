"""Tests that the incremental compilation setting reaches the KotlinCompile action."""

load("@rules_testing//lib:analysis_test.bzl", "analysis_test")
load("//src/test/starlark:case.bzl", "suite")
load(":arrangement.bzl", "arrange")
load(":util.bzl", "values_for_flag_of")

_SETTING_CONTROLLED_RUNTIME_TOOLCHAIN = str(Label("@rules_kotlin//src/test/starlark/rules:setting_controlled_runtime_toolchain"))
_INCREMENTAL_COMPILATION = str(Label("@rules_kotlin//kotlin/settings:experimental_incremental_compilation"))

def _count(values):
    return [str(len(values))]

def _incremental_assertion(env, target):
    action = env.expect.that_target(target).action_named("KotlinCompile")
    values_for_flag_of(action, "--incremental_compilation").contains_exactly(["true"])

    # Incremental compilation runs through the Build Tools API, so the runtime comes along.
    values_for_flag_of(action, "--btapi_impl_classpath").transform(
        desc = "count of --btapi_impl_classpath",
        loop = _count,
    ).not_contains("0")

def _non_incremental_assertion(env, target):
    action = env.expect.that_target(target).action_named("KotlinCompile")
    values_for_flag_of(action, "--incremental_compilation").contains_exactly([])

def _make_test(setting_value, assertion, extra_toolchain = None):
    def _case(test):
        (_dependency_a_trans_dep_jar, _dependency_a, main_target_library) = arrange(test)

        config_settings = {_INCREMENTAL_COMPILATION: setting_value}
        if extra_toolchain:
            config_settings["//command_line_option:extra_toolchains"] = [extra_toolchain]

        analysis_test(
            name = test.name,
            impl = assertion,
            target = main_target_library,
            config_settings = config_settings,
        )

    return _case

def incremental_compilation_tests(name):
    suite(
        name,
        incremental_actions_compile_incrementally_through_the_build_tools_api = _make_test(
            True,
            _incremental_assertion,
        ),
        default_actions_compile_non_incrementally = _make_test(
            False,
            _non_incremental_assertion,
        ),
        setting_overrides_a_toolchain_without_the_build_tools_api = _make_test(
            True,
            _incremental_assertion,
            extra_toolchain = _SETTING_CONTROLLED_RUNTIME_TOOLCHAIN,
        ),
    )
