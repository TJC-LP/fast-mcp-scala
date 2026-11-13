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
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    set -eu; \
    cargo build --release --locked --target x86_64-unknown-linux-gnu; \
    cp target/x86_64-unknown-linux-gnu/release/driver /driver

# Named here because not every engine applies a compose file's `platform:` to
# a build.
FROM --platform=linux/amd64 debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f
# curl and jq are the debug tools `snouty runs exec` can use in this container.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl jq \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /driver /usr/local/bin/driver
COPY --chmod=755 first_start_workload /opt/antithesis/test/v1/main/first_start_workload
CMD ["/usr/local/bin/driver"]
