# Copyright 2018 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import unittest

from common import BazelKotlinTestCase


class TestRules(BazelKotlinTestCase):
    def test_merge_resource_jar(self):
        jar = self.buildJarGetZipFile("test_merge_resourcesjar", "jar")
        self.assertJarContains(jar, "testresources/AClass.class", "testresources/BClass.class")
        self.assertJarContains(jar, "pkg/file.txt")

    def test_embed_resources(self):
        jar = self.buildJarGetZipFile("test_embed_resources", "jar")
        self.assertJarContains(jar, "testresources/AClass.class", "testresources/BClass.class")
        self.assertJarContains(jar, "tests/smoke/testresources/resources/one/two/aFile.txt", "tests/smoke/testresources/resources/one/alsoAFile.txt")

    def test_embed_resources_strip_prefix(self):
        jar = self.buildJarGetZipFile("test_embed_resources_strip_prefix", "jar")
        self.assertJarContains(jar, "testresources/AClass.class", "testresources/BClass.class")
        self.assertJarContains(jar, "one/two/aFile.txt", "one/alsoAFile.txt")

    def test_test_targets_launch_correctly(self):
        self.buildLaunchExpectingSuccess("junittest", command="test")

    def test_bin_targets_launch_correctly_with_data(self):
        self.buildLaunchExpectingSuccess("helloworld")

    def test_uses_target_name_as_default_module_name(self):
        """tests that the target name is used as the default module name."""
        jar = self.buildJarGetZipFile("helloworld", "jar")
        self.assertJarContains(jar, "META-INF/tests_smoke-helloworld.kotlin_module")

    def test_module_name_is_used_if_provided(self):
        """tests that the target name is used as the default module name."""
        jar = self.buildJarGetZipFile("modulename", "jar")
        self.assertJarContains(jar, "META-INF/hello-module.kotlin_module")

    def test_conventional_strip_resources(self):
        jar = self.buildJarGetZipFile("conventional_strip_resources", "jar")
        self.assertJarContains(jar, "main.txt", "test.txt")

    def test_export_ct_propagation(self):
        self.build("propagation_ct_consumer")

    def test_export_ct_propagation_fail_on_runtime(self):
        self.buildJarExpectingFail("propagation_ct_consumer_fail_on_runtime")

    def test_export_rt_propagation(self):
        self.buildLaunchExpectingSuccess("propagation_rt_via_export_consumer")

    def test_export_rt_propagation_via_dep(self):
        self.buildLaunchExpectingSuccess("propagation_rt_via_runtime_deps_consumer")


class MixedModeCompilation(BazelKotlinTestCase):
    def test_mixed_mode_compilation(self):
        jar = self.buildJarGetZipFile("hellojava", "jar")

        self.assertJarContains(
            jar,
            "hellojava/HelloWorldJava.class",
            "hellojava/MessageHolderKotlin.class",
            "hellojava/MessageHolder.class",
            "hellojava/HelloWorldKt.class"
        )

    def test_mixed_mode_compilation_with_merge(self):
        jar = self.buildJarGetZipFile("hellojava_withmerge", "jar")
        self.assertJarContains(
            jar,
            "hellojava/HelloWorldJava.class",
            "hellojava/MessageHolderKotlin.class",
            "hellojava/MessageHolder.class",
            "hellojava/HelloWorldKt.class",
            "tests/smoke/resourcejar/pkg/file.txt"
        )


if __name__ == '__main__':
    unittest.main()
