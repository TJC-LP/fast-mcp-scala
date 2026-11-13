# The server under test for the Antithesis workload: fast-mcp-scala on the JVM.
#
# The jars are built on the host by `scripts/build.sh` (`./mill fast-mcp-scala.antithesisSut.stage`)
# and copied in from `sut/stage/`: JVM bytecode is the same on every platform, so nothing is
# compiled under emulation. The context is this directory (`antithesis/mcp/sut`).
#
# Layout the Antithesis Java instrumentation needs:
#   /opt/app/bin   this project's jars; /opt/antithesis/catalog points here, so Antithesis
#                  instruments them and catalogs the SDK assertions in them
#   /opt/app/lib   third-party jars, on the classpath but not instrumented
# The JVM is started with both directories on the classpath (never `java -jar`), because
# Antithesis adds its own jars next to the application's.
FROM --platform=linux/amd64 eclipse-temurin:21-jre-jammy
# curl and jq are the debug tools `snouty runs exec` can use in this container.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*
COPY stage/bin /opt/app/bin
COPY stage/lib /opt/app/lib
RUN mkdir -p /opt/antithesis && ln -s /opt/app/bin /opt/antithesis/catalog
ENV SUT_PORT=8000 SUT_BACKEND=netty
# `exec` so the JVM is the container's main process and receives its signals.
CMD ["sh", "-c", "exec java -XX:+ExitOnOutOfMemoryError -Xmx512m -cp '/opt/app/bin/*:/opt/app/lib/*' com.tjclp.fastmcp.antithesis.AntithesisSut \"$SUT_PORT\" \"$SUT_BACKEND\""]
