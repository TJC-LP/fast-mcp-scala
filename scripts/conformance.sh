#!/usr/bin/env bash
#
# Run the official MCP conformance server suites against fast-mcp-scala.
#
#   scripts/conformance.sh [jvm|js|native] [port] [active|2026]
#
# Boots the cross-platform ConformanceServer (com.tjclp.fastmcp.examples.conformance.*) over
# streamable HTTP, then drives it with the official harness installed from the committed,
# integrity-hashed lockfile in conformance/ (bun install --frozen-lockfile — never bunx). Exit code
# follows the harness (0 = all scored scenarios pass or match the platform baseline; 1 = regression /
# stale baseline).
#
# Modes: "active" (default) runs the active suite across both protocol eras with the per-platform
# baseline; "2026" runs `--requirements 2026-07-28` — exactly the scenarios that revision requires,
# frozen at its release (extension/pending scenarios are reported but not scored by the harness).
#
# Requires: a JDK (jvm) and Bun >= 1.4 (every platform). The script prefers the Mill-managed Bun
# (fast-mcp-scala.js.bunExecutable — the same SHA-256-verified 1.4.1 that generated conformance/bun.lock),
# then $FAST_MCP_BUN, then PATH. The harness is resolved ONLY from conformance/package.json +
# conformance/bun.lock: a run whose resolution differs from the committed lock fails before the suite
# starts. To bump: edit the version in conformance/package.json, then
#   "$(./mill --no-server show fast-mcp-scala.js.bunExecutable | tr -d '"')" install --cwd conformance
# and review the bun.lock diff (it, not package.json, is what --frozen-lockfile enforces).
#
# "native" runs the SAME conformance server compiled to a GraalVM native image
# (fast-mcp-scala.nativeSmoke.http.nativeImage; override with FAST_MCP_NATIVE_BIN) against the
# UNCHANGED jvm baseline — the binary is behaviorally the same server, so any divergence is a
# native-image bug to fix, never a new baseline.
set -euo pipefail

PLATFORM="${1:-jvm}"
PORT="${2:-8077}"
MODE="${3:-active}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
HARNESS_DIR="$ROOT/conformance"   # committed package.json + bun.lock: the ONLY source of the harness
BUN=""                            # resolved by resolve_bun
BUN_CACHE=""                      # fresh per-run Bun install cache, removed in cleanup
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
  [ -n "$BUN_CACHE" ] && rm -rf "$BUN_CACHE" 2>/dev/null || true
}
trap cleanup EXIT

# Pick the Bun that runs the harness (and the JS server). conformance/bun.lock is lockfileVersion 2,
# which Bun < 1.4 cannot read (it prints UnknownLockfileVersion and then 'lockfile had changes'), so
# prefer the Mill-managed 1.4.1 — the binary that generated the lock and that js.test uses — and
# gate the fallbacks on version.
resolve_bun() {
  local b="${FAST_MCP_BUN:-}"
  if [ -z "$b" ]; then
    b="$(./mill --no-server show fast-mcp-scala.js.bunExecutable 2>/dev/null | tr -d '"' || true)"
  fi
  if [ -z "$b" ] || [ ! -x "$b" ]; then b="$(command -v bun || true)"; fi
  [ -n "$b" ] || { echo "bun not found (Mill-managed, \$FAST_MCP_BUN, or PATH) — cannot install the conformance harness" >&2; exit 2; }
  local v; v="$("$b" --version)"
  case "$v" in
    0.*|1.[0-3].*) echo "bun $v at $b is too old for conformance/bun.lock (lockfileVersion 2 needs Bun >= 1.4); use the Mill-managed Bun or set FAST_MCP_BUN" >&2; exit 2 ;;
  esac
  BUN="$b"
  echo "→ bun $v ($BUN)" >&2
}

# Install the harness from the committed lockfile — never from a floating registry resolution.
#  * --frozen-lockfile does NOT fail when bun.lock is absent: Bun resolves from the registry and
#    installs anyway (rc=0, no lock written). The lock's presence is therefore asserted first.
#  * Bun verifies sha512 only when it EXTRACTS a tarball; a warm ~/.bun/install/cache entry for
#    name@version is reused unverified. A fresh, empty cache per run forces every tarball through
#    the integrity check (~118 small packages; a few seconds).
#  * node_modules is wiped first: a failed install leaves a partial tree behind.
install_harness() {
  if [ ! -s "$HARNESS_DIR/bun.lock" ]; then
    echo "conformance/bun.lock is missing — refusing to resolve the harness from the registry" >&2
    exit 2
  fi
  local want; want="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dependencies"]["@modelcontextprotocol/conformance"])' "$HARNESS_DIR/package.json")"
  echo "→ installing conformance harness @${want} (frozen lockfile, fresh install cache)" >&2
  BUN_CACHE="$(mktemp -d -t fmcp-bun-cache.XXXXXX)"
  rm -rf "$HARNESS_DIR/node_modules"
  ( cd "$HARNESS_DIR" && BUN_INSTALL_CACHE_DIR="$BUN_CACHE" "$BUN" install --frozen-lockfile --ignore-scripts --no-summary )
}

# `bun run` only executes what the frozen install put in node_modules/.bin (rc=1 'Script not
# found' otherwise); `bunx` would auto-install a same-named package from the registry.
run_harness() {
  ( cd "$HARNESS_DIR" && exec "$BUN" run conformance "$@" )
}

# Refuse to run if ANYTHING already listens on the port — the readiness poll below would
# otherwise happily bless a foreign server and the suite would test the wrong process.
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  exec 3>&- 3<&- 2>/dev/null || true
  echo "port $PORT is already in use — refusing to test an unknown server" >&2
  exit 2
fi

# Resolve Bun and install the pinned harness BEFORE any server starts: a lock/Bun failure then needs
# no teardown and cannot be confused with a server failure.
resolve_bun
install_harness

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
  "$BUN" run "$ENTRY" "$PORT" >"$LOG" 2>&1 &
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
    run_harness server --url "$URL" --suite active --expected-failures "$BASELINE"
    ;;
  2026)
    echo "→ conformance 2026-07-28 requirements ($PLATFORM)" >&2
    run_harness server --url "$URL" --requirements 2026-07-28
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
