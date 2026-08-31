#!/usr/bin/env bash
#
# Run the official MCP conformance server suites against fast-mcp-scala.
#
#   scripts/conformance.sh [jvm|js|native] [port] [active|2026]
#
# Boots the cross-platform ConformanceServer (com.tjclp.fastmcp.examples.conformance.*) over
# streamable HTTP, then drives it with the official harness via `bunx`. Exit code follows the harness
# (0 = all scored scenarios pass or match the platform baseline; 1 = regression / stale baseline).
#
# Modes: "active" (default) runs the active suite across both protocol eras with the per-platform
# baseline; "2026" runs `--requirements 2026-07-28` — exactly the scenarios that revision requires,
# frozen at its release (extension/pending scenarios are reported but not scored by the harness).
#
# Requires: a JDK (jvm), bun (both). No vendored conformance checkout — the engine is fetched by bunx.
#
# "native" runs the SAME conformance server compiled to a GraalVM native image
# (fast-mcp-scala.nativeSmoke.http.nativeImage; override with FAST_MCP_NATIVE_BIN) against the
# UNCHANGED jvm baseline — the binary is behaviorally the same server, so any divergence is a
# native-image bug to fix, never a new baseline.
set -euo pipefail

PLATFORM="${1:-jvm}"
PORT="${2:-8077}"
MODE="${3:-active}"
CONF_VERSION="0.2.0-alpha.11" # RC1 oracle: first release with the 2026-07-28 scenario set; bump deliberately
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
URL="http://127.0.0.1:${PORT}/mcp"
BASELINE="$ROOT/conformance/baseline-${PLATFORM}.yml"
[ "$PLATFORM" = "native" ] && BASELINE="$ROOT/conformance/baseline-jvm.yml"
LOG="$(mktemp -t fmcp-conf-log.XXXXXX)"
SRV_PID=""
SRV_STOPPED=""
ENTRY=""

# Terminate the launched server and record HOW it died: "clean" (exited within 15s of SIGTERM)
# or "hang" (needed SIGKILL). Idempotent; used both on the normal path (so the completed log can
# be inspected afterwards) and from the EXIT trap.
stop_server() {
  [ -n "$SRV_PID" ] || return 0
  kill -TERM "$SRV_PID" 2>/dev/null || true
  for _ in $(seq 1 30); do
    kill -0 "$SRV_PID" 2>/dev/null || { SRV_STOPPED="clean"; break; }
    sleep 0.5
  done
  if [ "$SRV_STOPPED" != "clean" ]; then
    SRV_STOPPED="hang"
    kill -9 "$SRV_PID" 2>/dev/null || true
  fi
  wait "$SRV_PID" 2>/dev/null || true
  SRV_PID=""
}

cleanup() {
  stop_server
  [ -n "$ENTRY" ] && rm -f "$ENTRY" 2>/dev/null || true
}
trap cleanup EXIT

# Refuse to run if ANYTHING already listens on the port — the readiness poll below would
# otherwise happily bless a foreign server and the suite would test the wrong process.
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  exec 3>&- 3<&- 2>/dev/null || true
  echo "port $PORT is already in use — refusing to test an unknown server" >&2
  exit 2
fi

jvm_classpath() {
  ./mill --no-server show fast-mcp-scala.jvm.runClasspath 2>/dev/null |
    python3 -c "import sys,json,re; print(':'.join(re.sub(r'^q?ref:v\\d+:[0-9a-f]+:','',p) for p in json.load(sys.stdin)),end='')"
}

start_jvm() {
  echo "→ building JVM runtime classpath" >&2
  local cp; cp="$(jvm_classpath)"
  echo "→ launching ConformanceServerJvm on :$PORT" >&2
  java -cp "$cp" com.tjclp.fastmcp.examples.conformance.ConformanceServerJvm "$PORT" >"$LOG" 2>&1 &
  SRV_PID=$!
}

start_js() {
  echo "→ linking JS (fastLinkJS)" >&2
  ./mill --no-server fast-mcp-scala.js.fastLinkJS >/dev/null 2>&1
  local dest; dest="$(./mill --no-server show fast-mcp-scala.js.fastLinkJS 2>/dev/null |
    python3 -c "import sys,json,re; print(re.sub(r'^ref:v\\d+:[0-9a-f]+:','',json.load(sys.stdin)['dest']))")"
  ENTRY="$(mktemp -t fmcp-conf-entry.XXXXXX).mjs"
  printf 'import { startConformance } from "%s/main.js";\nstartConformance(Number(process.argv[2] ?? %s));\n' "$dest" "$PORT" >"$ENTRY"
  echo "→ launching ConformanceServerJs on :$PORT (Bun)" >&2
  bun run "$ENTRY" "$PORT" >"$LOG" 2>&1 &
  SRV_PID=$!
}

start_native() {
  local bin="${FAST_MCP_NATIVE_BIN:-}"
  if [ -z "$bin" ]; then
    echo "→ building native ConformanceServer (GraalVM)" >&2
    ./mill --no-server fast-mcp-scala.nativeSmoke.http.nativeImage >/dev/null 2>&1
    bin="$(./mill --no-server show fast-mcp-scala.nativeSmoke.http.nativeImage 2>/dev/null |
      python3 -c "import sys,json,re; print(re.sub(r'^q?ref:v\\d+:[0-9a-f]+:','',json.load(sys.stdin)))")"
  fi
  [ -x "$bin" ] || { echo "native binary not found/executable: $bin" >&2; exit 1; }
  echo "→ launching native ConformanceServer on :$PORT ($bin)" >&2
  "$bin" "$PORT" >"$LOG" 2>&1 &
  SRV_PID=$!
}

case "$PLATFORM" in
  jvm) start_jvm ;;
  js) start_js ;;
  native) start_native ;;
  *) echo "usage: $0 [jvm|js|native] [port]" >&2; exit 2 ;;
esac

echo "→ waiting for $URL" >&2
for i in $(seq 1 90); do
  # The child must still be alive AND answering — a dead child with a lingering listener (or a
  # foreign process) must never pass readiness.
  if ! kill -0 "$SRV_PID" 2>/dev/null; then
    echo "server process died during startup; log:" >&2; cat "$LOG" >&2; exit 1
  fi
  curl -s -o /dev/null "$URL" 2>/dev/null && break
  if [ "$i" -eq 90 ]; then echo "server failed to start; log:" >&2; cat "$LOG" >&2; exit 1; fi
  sleep 0.5
done

set +e
case "$MODE" in
  active)
    echo "→ conformance active suite ($PLATFORM, baseline $(basename "$BASELINE"))" >&2
    bunx "@modelcontextprotocol/conformance@${CONF_VERSION}" \
      server --url "$URL" --suite active --expected-failures "$BASELINE"
    ;;
  2026)
    echo "→ conformance 2026-07-28 requirements ($PLATFORM)" >&2
    bunx "@modelcontextprotocol/conformance@${CONF_VERSION}" \
      server --url "$URL" --requirements 2026-07-28
    ;;
  *) echo "usage: $0 [jvm|js|native] [port] [active|2026]" >&2; exit 2 ;;
esac
RC=$?
set -e
# Terminate BEFORE inspecting the log, so teardown-time failures are gated too.
stop_server
echo "→ server log: $LOG" >&2
if [ "$PLATFORM" = "native" ]; then
  # --install-exit-handlers must actually work: a binary that survives 15s of SIGTERM is a bug
  # (dead event loops deadlocking shutdown was exactly the failure SharedArenaSupport fixed).
  if [ "$SRV_STOPPED" = "hang" ]; then
    echo "native FAIL: server did not exit within 15s of SIGTERM (suite verdict: $RC)" >&2
    exit 1
  fi
  # Native binaries can shed threads on GraalVM UnsupportedFeatureError while the suite still
  # passes on the surviving event loops — treat any such error as a failure.
  if grep -q "UnsupportedFeatureError" "$LOG"; then
    echo "native FAIL: UnsupportedFeatureError in server log (suite verdict: $RC)" >&2
    grep -m1 -A3 "UnsupportedFeatureError" "$LOG" >&2
    exit 1
  fi
fi
exit "$RC"
