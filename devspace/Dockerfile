# PURPOSE: DevSpace Pod runtime image for rules_kotlin, used by Speck sessions.
# PURPOSE: Bazel, a JDK, a C++ toolchain, protoc, and the Starlark/shell linters this repo's work needs.
#
# Built by the image-only Gondola pipeline in devspace/gondola.yaml. Modeled on
# tinyspeck/slack-android-ng's devspace/Dockerfile, with the same base image and
# tool pins where the two overlap.

FROM 388288586445.dkr.ecr.us-east-1.amazonaws.com/ubuntu:22.04

ENV TZ=America/Los_Angeles
ENV DEBIAN_FRONTEND=noninteractive

# The Bazel version in .bazelversion, which upstream sets and slack-android-ng
# is moving to (tinyspeck/slack-android-ng#129113). bazelisk reads .bazelversion
# at runtime, so a branch that pins something else still works; it just
# downloads that version on first use instead of finding it here. Bump this with
# .bazelversion when master syncs a new one from upstream. Older versions are
# deliberately not baked in: the integration tests download their own 7.x/8.x
# through bazel_binaries.
ARG BAZEL_VERSION=9.3.1
ARG BAZELISK_VERSION=v1.29.0
# Keep in sync with the buildifier task in .bazelci/presubmit.yml.
ARG BUILDIFIER_VERSION=v8.5.1
# Keep in sync with proto_version in MODULE.bazel. Bazel builds with its own
# protoc; this one is for inspecting and hand-compiling the worker protos.
ARG PROTOC_VERSION=33.4
ARG SHELLCHECK_VERSION=v0.10.0
ARG BATS_VERSION=v1.11.1
ARG RIPGREP_VERSION=14.1.1
ARG FD_VERSION=10.2.0
ARG SHFMT_VERSION=v3.10.0
ARG ASTGREP_VERSION=0.39.5

# build-essential gives Bazel's autodetected C++ toolchain a compiler: protobuf,
# the worker's native deps and rules_cc targets need one, and slack-android-ng's
# image (which pods borrowed before this one) has none.
#
# OpenJDK 17 from apt rather than a pinned Zulu tarball: rules_kotlin's
# .bazelrc uses remotejdk_17, so the local JDK only runs tools and Bazel itself,
# and the apt package stays patched with each image rebuild. It also lives on
# /usr/bin, so the PAM /etc/environment PATH clobber slack-android-ng's image
# works around with a profile.d script doesn't apply here.
# hadolint ignore=DL3008
RUN apt-get update && apt-get install --no-install-recommends -y \
      build-essential \
      ca-certificates \
      curl \
      git \
      jq \
      less \
      openjdk-17-jdk-headless \
      python3 \
      unzip \
      xz-utils \
      zip \
    && rm -rf /var/lib/apt/lists/* \
    && java -version && gcc --version && g++ --version
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# bazelisk is the supported entrypoint: it honours .bazelversion, so `bazel` in a
# pod resolves the same version CI uses. Pre-seeding the release means the first
# build in a fresh pod doesn't pay the download.
RUN curl -fsSL -o /usr/local/bin/bazelisk \
      "https://github.com/bazelbuild/bazelisk/releases/download/${BAZELISK_VERSION}/bazelisk-linux-amd64" \
    && chmod +x /usr/local/bin/bazelisk \
    && ln -s /usr/local/bin/bazelisk /usr/local/bin/bazel \
    && USE_BAZEL_VERSION="${BAZEL_VERSION}" bazelisk version

# Starlark and protobuf tooling. devspace/speck_bazel.sh buildifier-check/-fix
# prefer buildifier on PATH, with the warnings list from .bazelci/presubmit.yml,
# over //:buildifier.check, so they don't need a Bazel build first.
RUN curl -fsSL -o /usr/local/bin/buildifier \
      "https://github.com/bazelbuild/buildtools/releases/download/${BUILDIFIER_VERSION}/buildifier-linux-amd64" \
    && chmod +x /usr/local/bin/buildifier \
    && curl -fsSL -o /tmp/protoc.zip \
      "https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip" \
    && unzip -q -o /tmp/protoc.zip -d /usr/local bin/protoc 'include/*' \
    && chmod +x /usr/local/bin/protoc \
    && rm /tmp/protoc.zip \
    && buildifier --version && protoc --version

# Shell tooling: shellcheck from its release (Ubuntu 22.04's apt package is
# 0.8.0), bats-core for devspace/test, and shfmt.
RUN curl -fsSL -o /tmp/shellcheck.tar.xz \
      "https://github.com/koalaman/shellcheck/releases/download/${SHELLCHECK_VERSION}/shellcheck-${SHELLCHECK_VERSION}.linux.x86_64.tar.xz" \
    && tar -xJf /tmp/shellcheck.tar.xz -C /tmp \
    && mv "/tmp/shellcheck-${SHELLCHECK_VERSION}/shellcheck" /usr/local/bin/shellcheck \
    && curl -fsSL -o /tmp/bats.tar.gz \
      "https://github.com/bats-core/bats-core/archive/refs/tags/${BATS_VERSION}.tar.gz" \
    && mkdir -p /tmp/bats \
    && tar -xzf /tmp/bats.tar.gz -C /tmp/bats --strip-components=1 \
    && /tmp/bats/install.sh /usr/local \
    && curl -fsSL -o /usr/local/bin/shfmt \
      "https://github.com/mvdan/sh/releases/download/${SHFMT_VERSION}/shfmt_${SHFMT_VERSION}_linux_amd64" \
    && chmod +x /usr/local/bin/shellcheck /usr/local/bin/shfmt \
    && rm -rf /tmp/shellcheck.tar.xz "/tmp/shellcheck-${SHELLCHECK_VERSION}" /tmp/bats.tar.gz /tmp/bats \
    && shellcheck --version && bats --version && shfmt --version

# Search and code-navigation tools, the same pins as slack-android-ng's image.
# ast-grep handles structural Kotlin and Starlark queries that regex can't.
RUN curl -fsSL -o /tmp/rg.tar.gz \
      "https://github.com/BurntSushi/ripgrep/releases/download/${RIPGREP_VERSION}/ripgrep-${RIPGREP_VERSION}-x86_64-unknown-linux-musl.tar.gz" \
    && tar -xzf /tmp/rg.tar.gz -C /tmp \
    && mv "/tmp/ripgrep-${RIPGREP_VERSION}-x86_64-unknown-linux-musl/rg" /usr/local/bin/rg \
    && curl -fsSL -o /tmp/fd.tar.gz \
      "https://github.com/sharkdp/fd/releases/download/v${FD_VERSION}/fd-v${FD_VERSION}-x86_64-unknown-linux-musl.tar.gz" \
    && tar -xzf /tmp/fd.tar.gz -C /tmp \
    && mv "/tmp/fd-v${FD_VERSION}-x86_64-unknown-linux-musl/fd" /usr/local/bin/fd \
    && curl -fsSL -o /tmp/ast-grep.zip \
      "https://github.com/ast-grep/ast-grep/releases/download/${ASTGREP_VERSION}/app-x86_64-unknown-linux-gnu.zip" \
    && unzip -q -o /tmp/ast-grep.zip -d /usr/local/bin \
    && chmod +x /usr/local/bin/rg /usr/local/bin/fd /usr/local/bin/sg /usr/local/bin/ast-grep \
    && rm -rf /tmp/rg.tar.gz /tmp/fd.tar.gz /tmp/ast-grep.zip \
      "/tmp/ripgrep-${RIPGREP_VERSION}-x86_64-unknown-linux-musl" \
      "/tmp/fd-v${FD_VERSION}-x86_64-unknown-linux-musl" \
    && rg --version && fd --version && ast-grep --version
