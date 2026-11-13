# Hegel driver image for the fast-mcp-scala Antithesis workload.
#
# The build stage runs on the build host's platform and cross-compiles to
# x86-64 Linux (the platform Antithesis runs), so an Apple Silicon host needs
# no emulation. The context is this directory.

FROM --platform=$BUILDPLATFORM rust:1.98-bookworm AS build
ARG BUILDARCH
RUN set -eu; \
    if [ "${BUILDARCH}" != amd64 ]; then \
      apt-get update; \
      apt-get install -y --no-install-recommends gcc-x86-64-linux-gnu libc6-dev-amd64-cross; \
      rm -rf /var/lib/apt/lists/*; \
    fi; \
    rustup target add x86_64-unknown-linux-gnu
ENV CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=x86_64-linux-gnu-gcc \
    CC_x86_64_unknown_linux_gnu=x86_64-linux-gnu-gcc
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
# The specification's JSON Schemas, compiled into the binary (`src/wire.rs`).
COPY schema ./schema
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    set -eu; \
    cargo build --release --locked --target x86_64-unknown-linux-gnu; \
    cp target/x86_64-unknown-linux-gnu/release/driver /driver

# The official MCP conformance harness, which the driver runs once faults have stopped, and the
# Node it runs under.
#
# The harness is installed from `stage/conformance/`: the repository's `conformance/package.json`
# and `conformance/bun.lock`, copied there by `scripts/build.sh`. The install is frozen, so the
# lock decides every version and every tarball is checked against the lock's hash (the cache is
# fresh, so each one is extracted and checked). Never `npm install` or `npx` here: neither reads
# the lock. This is what `scripts/conformance.sh` does on a workstation and in CI.
#
# Bun does the install and is not in the final image. It is the release archive, pinned by
# version and checksum, the same as in `sut/Dockerfile.bun` (bump both together). This stage runs
# on the build host's platform, so nothing here runs under emulation; the packages are plain
# JavaScript and Node is copied out as a file.
#
# Node is the x86-64 release archive, pinned by version and checksum (from the release's
# SHASUMS256.txt). Only the `node` binary is kept.
FROM --platform=$BUILDPLATFORM debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f AS harness
ARG BUN_VERSION=1.4.1
ARG NODE_VERSION=26.13.1
ARG NODE_SHA256=518674de2b621728a5e4486f5fb3b7d2900bb3cc4aa97969edaa463db99f9778
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    case "$(dpkg --print-architecture)" in \
      amd64) asset=bun-linux-x64-baseline; sum=a8c9c6738202e2fced555dd860a953c56c0cd059f75041e7010ae81a32802646 ;; \
      arm64) asset=bun-linux-aarch64; sum=580ce77533108dc6b10bec1721397e4f5aa44e909726da2451d483dfc5e581d6 ;; \
      *) echo "unsupported architecture" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/bun.zip "https://github.com/oven-sh/bun/releases/download/bun-v${BUN_VERSION}/${asset}.zip"; \
    echo "${sum}  /tmp/bun.zip" | sha256sum -c -; \
    unzip -q /tmp/bun.zip -d /tmp; \
    install -m 755 "/tmp/${asset}/bun" /usr/local/bin/bun; \
    rm -rf /tmp/bun.zip "/tmp/${asset}"
RUN set -eu; \
    dist="node-v${NODE_VERSION}-linux-x64"; \
    curl -fsSL -o /tmp/node.tar.gz "https://nodejs.org/dist/v${NODE_VERSION}/${dist}.tar.gz"; \
    echo "${NODE_SHA256}  /tmp/node.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/node.tar.gz -C /tmp "${dist}/bin/node"; \
    install -m 755 "/tmp/${dist}/bin/node" /node; \
    rm -rf /tmp/node.tar.gz "/tmp/${dist}"
WORKDIR /opt/conformance
COPY stage/conformance/package.json stage/conformance/bun.lock ./
# `--frozen-lockfile` does not fail when the lock is absent, so its presence is checked first.
RUN set -eu; \
    test -s bun.lock; \
    BUN_INSTALL_CACHE_DIR=/tmp/bun-cache bun install --frozen-lockfile --ignore-scripts --no-summary; \
    rm -rf /tmp/bun-cache; \
    test -x node_modules/.bin/conformance
# The baselines the harness is given, and the manifest the driver reads (see the script).
COPY stage/conformance/baseline-jvm.yml stage/conformance/baseline-js.yml conformance-manifest.mjs ./
RUN bun conformance-manifest.mjs > manifest.json && rm conformance-manifest.mjs

# Named here because not every engine applies a compose file's `platform:` to
# a build.
FROM --platform=linux/amd64 debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f
# curl and jq are the debug tools `snouty runs exec` can use in this container. libatomic1 is
# the one shared library the Node binary needs that the base image lacks.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl jq libatomic1 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=harness /node /usr/local/bin/node
COPY --from=harness /opt/conformance /opt/conformance
# Fails the build, not a run, if Node or the harness cannot start in this image.
RUN node --version && /opt/conformance/node_modules/.bin/conformance --version
COPY --from=build /driver /usr/local/bin/driver
COPY --chmod=755 first_start_workload /opt/antithesis/test/v1/main/first_start_workload
CMD ["/usr/local/bin/driver"]
