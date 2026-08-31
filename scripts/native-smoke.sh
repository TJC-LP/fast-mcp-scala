#!/usr/bin/env bash
# Drive a fast-mcp-scala stdio MCP binary end-to-end over raw JSON-RPC (NDJSON) and assert the
# replies with jq. Proves that a GraalVM native image of an annotated stdio server works with
# ZERO hand-written reachability metadata (GH #66 / TJC-2114), and that the transport-seam split
# keeps netty/zio-http out of the binary entirely.
#
# Usage:
#   scripts/native-smoke.sh [path-to-binary]
#
# With no argument, resolves (and if needed builds) the
# `fast-mcp-scala.nativeSmoke.stdio.nativeImage` output via mill.
#
# NATIVE_SMOKE_CMD overrides the server command entirely — e.g. a JVM run under
# `-agentlib:native-image-agent` when regenerating metadata (see docs/native-image.md). The
# binary-only checks (netty-shed) are skipped in that mode.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v jq >/dev/null 2>&1 || { echo "native-smoke: jq is required" >&2; exit 1; }

BIN="${1:-}"
if [ -z "${NATIVE_SMOKE_CMD:-}" ] && [ -z "$BIN" ]; then
  echo "→ resolving native binary via mill" >&2
  BIN="$(./mill --no-server show fast-mcp-scala.nativeSmoke.stdio.nativeImage 2>/dev/null |
    python3 -c "import sys,json,re; print(re.sub(r'^q?ref:v\d+:[0-9a-f]+:','',json.load(sys.stdin)))")"
fi
if [ -z "${NATIVE_SMOKE_CMD:-}" ]; then
  [ -x "$BIN" ] || { echo "native-smoke: binary not found/executable: $BIN" >&2; exit 1; }
fi

WORK="$(mktemp -d -t fmcp-native.XXXXXX)"
OUT="$WORK/out"
ERR="$WORK/err"
SRV_PID=""
cleanup() {
  exec 3>&- 2>/dev/null || true
  [ -n "$SRV_PID" ] && kill "$SRV_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() {
  echo "native-smoke FAIL: $1" >&2
  echo "--- stdout ---" >&2; cat "$OUT" >&2
  echo "--- stderr ---" >&2; cat "$ERR" >&2
  exit 1
}

# True once the reply for $1 has landed in $OUT (tolerates a partially-written last line).
has_id() {
  jq -es --argjson id "$1" 'map(select(.id == $id)) | length >= 1' <"$OUT" >/dev/null 2>&1
}

await_id() {
  for _ in $(seq 1 120); do
    has_id "$1" && return 0
    sleep 0.5
  done
  fail "timed out waiting for reply id=$1"
}

# Requests are fed through a FIFO in two phases: the stdio loop dispatches each frame in its own
# fiber, so post-handshake requests must not be written until the initialize reply has landed
# (otherwise they race the handshake and get -32600 "Server not initialized").
mkfifo "$WORK/in"
echo "→ driving server over stdio" >&2
if [ -n "${NATIVE_SMOKE_CMD:-}" ]; then
  bash -c "$NATIVE_SMOKE_CMD" <"$WORK/in" >"$OUT" 2>"$ERR" &
else
  "$BIN" <"$WORK/in" >"$OUT" 2>"$ERR" &
fi
SRV_PID=$!
exec 3>"$WORK/in"

printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"native-smoke","version":"0.0.0"}}}' >&3
await_id 1
printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}' >&3

# One exercise of every registration kind on AnnotatedServer. tools/list is the load-bearing
# check: its inputSchema payloads are produced by the genuine RUNTIME tapir→circe
# schema-rendering path, exactly where closed-world analysis would break if reachability
# metadata were missing.
printf '%s\n' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add","arguments":{"a":2,"b":3}}}' \
  '{"jsonrpc":"2.0","id":4,"method":"prompts/list"}' \
  '{"jsonrpc":"2.0","id":5,"method":"prompts/get","params":{"name":"hello_prompt","arguments":{}}}' \
  '{"jsonrpc":"2.0","id":6,"method":"resources/list"}' \
  '{"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"static://welcome"}}' >&3

for id in 2 3 4 5 6 7; do await_id "$id"; done

# Close stdin → EOF must end the stdio loop and the process must exit 0 on its own; a hang or a
# nonzero status is a real defect (forced TERM/KILL would mask it), so both fail the smoke.
exec 3>&-
EXITED=""
for _ in $(seq 1 40); do
  kill -0 "$SRV_PID" 2>/dev/null || { EXITED=1; break; }
  sleep 0.25
done
if [ -z "$EXITED" ]; then
  kill -9 "$SRV_PID" 2>/dev/null || true
  fail "server did not exit on stdin EOF within 10s"
fi
SRV_RC=0
wait "$SRV_PID" || SRV_RC=$?
SRV_PID=""
[ "$SRV_RC" -eq 0 ] || fail "server exited with status $SRV_RC (expected 0 on clean EOF)"

# Replies may arrive out of order (each frame dispatches in its own fiber), so slurp and select.
for id in 1 2 3 4 5 6 7; do
  jq -es --argjson id "$id" \
    'map(select(.id == $id)) | length == 1 and (.[0] | has("result"))' \
    <"$OUT" >/dev/null 2>&1 || fail "no clean result for id=$id"
done

jq -es 'map(select(.id == 2))[0].result.tools | map(.name) | (index("add") != null) and (index("calculator") != null)' \
  <"$OUT" >/dev/null || fail "tools/list is missing add/calculator"
jq -es 'map(select(.id == 2))[0].result.tools[] | select(.name == "add") | .inputSchema.properties.a.type == "integer"' \
  <"$OUT" >/dev/null || fail "add inputSchema lost its runtime-derived properties"
jq -es 'map(select(.id == 3))[0].result.content[0].text == "5"' \
  <"$OUT" >/dev/null || fail "tools/call add(2,3) != 5"
jq -es 'map(select(.id == 4))[0].result.prompts | length > 0' \
  <"$OUT" >/dev/null || fail "prompts/list empty"
jq -es 'map(select(.id == 5))[0].result.messages | length > 0' \
  <"$OUT" >/dev/null || fail "prompts/get hello_prompt empty"
jq -es 'map(select(.id == 6))[0].result.resources | length > 0' \
  <"$OUT" >/dev/null || fail "resources/list empty"
jq -es 'map(select(.id == 7))[0].result.contents[0].text | length > 0' \
  <"$OUT" >/dev/null || fail "resources/read static://welcome unreadable"

# The seam-split regression guard: a stdio binary must not contain the HTTP stack. A handful of
# stray matches is tolerated for robustness across GraalVM versions; a re-coupled seam shows up
# as thousands (embedded class metadata).
if [ -z "${NATIVE_SMOKE_CMD:-}" ] && command -v strings >/dev/null 2>&1; then
  NETTY_COUNT="$(strings "$BIN" | grep -c 'io\.netty' || true)"
  ZIOHTTP_COUNT="$(strings "$BIN" | grep -c 'zio\.http' || true)"
  if [ "$NETTY_COUNT" -gt 5 ] || [ "$ZIOHTTP_COUNT" -gt 5 ]; then
    fail "HTTP stack leaked into the stdio binary (io.netty: $NETTY_COUNT, zio.http: $ZIOHTTP_COUNT strings)"
  fi
  echo "→ netty-shed check ok (io.netty: $NETTY_COUNT, zio.http: $ZIOHTTP_COUNT strings)" >&2
fi

echo "native stdio smoke: OK${BIN:+ ($BIN)}"
