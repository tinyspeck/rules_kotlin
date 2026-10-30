---
name: bump-slack-android
description: Point tinyspeck/slack-android-ng at a new rules_kotlin commit (RULES_KOTLIN_SHA and archive_override integrity) to verify a rules_kotlin change in Slack Android's Bazel CI.
---

# Bump slack-android-ng's rules_kotlin pin

slack-android-ng consumes this fork through `archive_override` in its
`MODULE.bazel`:

```starlark
RULES_KOTLIN_SHA = "<40-char commit>"

archive_override(
    module_name = "rules_kotlin",
    integrity = "sha256-<base64>",
    strip_prefix = "rules_kotlin-{}".format(RULES_KOTLIN_SHA),
    urls = ["https://github.com/tinyspeck/rules_kotlin/archive/{}.zip".format(RULES_KOTLIN_SHA)],
)
```

Its Bazel CI (Buildkite `android-bazel-ci`) is the real consumer check for a
rules_kotlin change. Do this only when the person asks: it opens a PR in
another repo.

## Steps

1. Push the rules_kotlin commit (usually on the newest
   `slack/v<kotlin version>-rebase` branch or a branch off it). GitHub serves
   archives only for pushed commits, and only while they stay reachable, so
   never force-push the branch the pin points into.
2. In the rules_kotlin pod:
   `devspace/speck_bazel.sh archive-integrity <full sha>`. It downloads the
   archive and prints the block above with the right `integrity`.
3. In a pod for tinyspeck/slack-android-ng on a new branch, replace
   `RULES_KOTLIN_SHA` and `integrity` with the printed values. Update the
   comment above the pin if the branch it points at changed (it names the
   upstream tag and the Slack commits on top).
4. Open a draft PR there that links the rules_kotlin commit or PR, and follow
   that repo's `SPECK.md` for watching Bazel CI.

## Notes

- Never hand-edit the integrity or reuse the old one: a mismatch fails every
  Bazel build in slack-android-ng.
- The pin is a commit, not a branch, so later pushes to the rules_kotlin branch
  don't change Slack Android until the pin moves again.
