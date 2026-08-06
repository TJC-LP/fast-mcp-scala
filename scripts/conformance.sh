#!/usr/bin/env bash
#
# Run the official MCP conformance "active" server suite against fast-mcp-scala.
#
#   scripts/conformance.sh [jvm|js] [port]
#
# Boots the cross-platform ConformanceServer (com.tjclp.fastmcp.examples.conformance.*) over
# streamable HTTP, then drives it with the official harness via `bunx`. Exit code follows the harness
# (0 = all active scenarios pass or match the platform baseline; 1 = regression / stale baseline).
#
# Requires: a JDK (jvm), bun (both). No vendored conformance checkout — the engine is fetched by bunx.
set -euo pipefail

PLATFORM="${1:-jvm}"
PORT="${2:-8077}"
CONF_VERSION="0.2.0-alpha.1" # pinned to match ~/git/conformance reference; bump deliberately
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
URL="http://127.0.0.1:${PORT}/mcp"
BASELINE="$ROOT/conformance/baseline-${PLATFORM}.yml"
LOG="$(mktemp -t fmcp-conf-log.XXXXXX)"
SRV_PID=""
ENTRY=""

cleanup() {
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null || true
  [ -n "$ENTRY" ] && rm -f "$ENTRY" 2>/dev/null || true
}
trap cleanup EXIT

jvm_classpath() {
  ./mill show fast-mcp-scala.jvm.runClasspath 2>/dev/null |
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
  ./mill fast-mcp-scala.js.fastLinkJS >/dev/null 2>&1
  local dest; dest="$(./mill show fast-mcp-scala.js.fastLinkJS 2>/dev/null |
    python3 -c "import sys,json,re; print(re.sub(r'^ref:v\\d+:[0-9a-f]+:','',json.load(sys.stdin)['dest']))")"
  ENTRY="$(mktemp -t fmcp-conf-entry.XXXXXX).mjs"
  printf 'import { startConformance } from "%s/main.js";\nstartConformance(Number(process.argv[2] ?? %s));\n' "$dest" "$PORT" >"$ENTRY"
  echo "→ launching ConformanceServerJs on :$PORT (Bun)" >&2
  bun run "$ENTRY" "$PORT" >"$LOG" 2>&1 &
  SRV_PID=$!
}

case "$PLATFORM" in
  jvm) start_jvm ;;
  js) start_js ;;
  *) echo "usage: $0 [jvm|js] [port]" >&2; exit 2 ;;
esac

echo "→ waiting for $URL" >&2
for i in $(seq 1 90); do
  curl -s -o /dev/null "$URL" 2>/dev/null && break
  if [ "$i" -eq 90 ]; then echo "server failed to start; log:" >&2; cat "$LOG" >&2; exit 1; fi
  sleep 0.5
done

echo "→ conformance active suite ($PLATFORM, baseline $(basename "$BASELINE"))" >&2
set +e
bunx "@modelcontextprotocol/conformance@${CONF_VERSION}" \
  server --url "$URL" --suite active --expected-failures "$BASELINE"
RC=$?
set -e
echo "→ server log: $LOG" >&2
exit "$RC"
