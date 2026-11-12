#!/usr/bin/env bash
#
# Throughput and latency of the fast-mcp-scala HTTP backends, measured with bench/mcpbench against
# the BenchServer example (com.tjclp.fastmcp.examples.bench).
#
#   bench/run.sh [arm ...]
#
# Arms (default: jvm-netty jvm-socket bun):
#   jvm-netty      JVM, ZIO HTTP (netty)             - the default JVM backend
#   jvm-socket     JVM, java.net.ServerSocket backend - JvmSocketHttpBackend
#   bun            Scala.js on Bun (Bun.serve), fullLinkJS output
#   scala-native   Scala Native binary, socket backend, linked release-fast (slow to link)
#
# Settings (environment):
#   MODES     protocol paths to measure        default "modern legacy stateless"
#               modern    MCP 2026-07-28: one self-describing request per POST
#               legacy    2025-11-25 session: initialize once per worker, then tools/call
#               stateless 2025-11-25 against a `stateless = true` server
#   CONC      worker counts                    default "1 16 64"
#   REPEATS   measured runs per cell           default 3 (the summary reports the median)
#   DURATION  length of one measured run       default 8s
#   WARMUP    load before the first cell       default 10s at 16 workers
#   PAYLOAD   bytes carried by request and reply (calls `echo`); 0 calls `add`   default 0
#   PORT      listen port                      default 18077
#   OUT       results file (JSON lines)        default bench/results/<utc timestamp>.jsonl
#   MAX_LOAD  refuse to start a group when the 1-minute load average is above this   default 4.0
#   ALLOW_LOAD=1 measures on a busy machine anyway (the load is still recorded)
#
# Each (arm, mode) gets a freshly started server, a warm-up, then every worker count REPEATS times.
# Results are appended to OUT as JSON lines and summarised by bench/summarize.py at the end.
#
# Requires: Go >= 1.22 (builds mcpbench), a JDK, python3, curl; Bun comes from Mill.
# Read bench/README.md before quoting a number.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ARMS=("$@")
[ ${#ARMS[@]} -gt 0 ] || ARMS=(jvm-netty jvm-socket bun)
read -r -a MODES <<<"${MODES:-modern legacy stateless}"
read -r -a CONC <<<"${CONC:-1 16 64}"
REPEATS="${REPEATS:-3}"
DURATION="${DURATION:-8s}"
WARMUP="${WARMUP:-10s}"
PAYLOAD="${PAYLOAD:-0}"
PORT="${PORT:-18077}"
OUT="${OUT:-$ROOT/bench/results/$(date -u +%Y%m%dT%H%M%SZ).jsonl}"
URL="http://127.0.0.1:${PORT}/mcp"
BENCH="$ROOT/bench/mcpbench/mcpbench"
LOG="$(mktemp -t fmcp-bench-log.XXXXXX)"
ENTRY=""
SRV_PID=""

for arm in "${ARMS[@]}"; do
  case "$arm" in
    jvm-netty|jvm-socket|bun|scala-native) ;;
    *) echo "unknown arm '$arm' (jvm-netty | jvm-socket | bun | scala-native)" >&2; exit 2 ;;
  esac
done

stop_server() {
  [ -n "$SRV_PID" ] || return 0
  kill -TERM "$SRV_PID" 2>/dev/null || true
  for _ in $(seq 1 20); do
    kill -0 "$SRV_PID" 2>/dev/null || break
    sleep 0.25
  done
  kill -9 "$SRV_PID" 2>/dev/null || true
  wait "$SRV_PID" 2>/dev/null || true
  SRV_PID=""
}

cleanup() {
  stop_server
  [ -n "$ENTRY" ] && rm -f "$ENTRY" 2>/dev/null || true
  rm -f "$LOG" 2>/dev/null || true
}
trap cleanup EXIT

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; }

# Mill prints paths as JSON, prefixed with ref:v0:<hash>: for PathRefs.
mill_show() { ./mill --no-server show "$1" 2>/dev/null; }
strip_ref() { python3 -c "import sys,json,re
v=json.load(sys.stdin)
f=lambda p: re.sub(r'^q?ref:v\\d+:[0-9a-f]+:','',p)
print(':'.join(f(p) for p in v) if isinstance(v,list) else f(v['dest'] if isinstance(v,dict) else v),end='')"; }

echo "→ building mcpbench" >&2
( cd "$ROOT/bench/mcpbench" && go build -o mcpbench . )

JVM_CP=""; BUN=""; JS_DEST=""; SN_BIN=""
for arm in "${ARMS[@]}"; do
  case "$arm" in
    jvm-*)
      if [ -z "$JVM_CP" ]; then
        echo "→ building the JVM runtime classpath" >&2
        ./mill --no-server fast-mcp-scala.jvm.compile >/dev/null 2>&1
        JVM_CP="$(mill_show fast-mcp-scala.jvm.runClasspath | strip_ref)"
      fi ;;
    bun)
      echo "→ linking the Scala.js bundle (fullLinkJS)" >&2
      ./mill --no-server fast-mcp-scala.js.fullLinkJS >/dev/null 2>&1
      JS_DEST="$(mill_show fast-mcp-scala.js.fullLinkJS | strip_ref)"
      BUN="${FAST_MCP_BUN:-$(mill_show fast-mcp-scala.js.bunExecutable | tr -d '"')}"
      [ -x "$BUN" ] || { echo "bun not found ($BUN)" >&2; exit 2; }
      ENTRY="$(mktemp -t fmcp-bench-entry.XXXXXX).mjs"
      printf 'import { startBench } from "%s/main.js";\nstartBench(Number(process.argv[2]), process.argv[3] === "stateless");\n' "$JS_DEST" >"$ENTRY" ;;
    scala-native)
      SN_BIN="${FAST_MCP_SCALA_NATIVE_BENCH_BIN:-}"
      if [ -z "$SN_BIN" ]; then
        echo "→ linking the Scala Native bench binary (release-fast; this takes minutes)" >&2
        SCALANATIVE_MODE=release-fast ./mill --no-server fast-mcp-scala.scalaNative.benchLink >/dev/null 2>&1
        SN_BIN="$(SCALANATIVE_MODE=release-fast mill_show fast-mcp-scala.scalaNative.benchLink | strip_ref)"
      fi
      [ -x "$SN_BIN" ] || { echo "Scala Native bench binary not found ($SN_BIN)" >&2; exit 2; } ;;
  esac
done

# `exec` inside the subshell so $! is the server itself, not a wrapper that survives its kill.
start_server() {
  local arm="$1" flag="$2"
  case "$arm" in
    jvm-netty)    ( exec java -cp "$JVM_CP" com.tjclp.fastmcp.examples.bench.BenchServerJvm "$PORT" netty $flag ) >"$LOG" 2>&1 & ;;
    jvm-socket)   ( exec java -cp "$JVM_CP" com.tjclp.fastmcp.examples.bench.BenchServerJvm "$PORT" socket $flag ) >"$LOG" 2>&1 & ;;
    bun)          ( exec "$BUN" run "$ENTRY" "$PORT" $flag ) >"$LOG" 2>&1 & ;;
    scala-native) ( exec "$SN_BIN" "$PORT" $flag ) >"$LOG" 2>&1 & ;;
  esac
  SRV_PID=$!
  for _ in $(seq 1 150); do
    kill -0 "$SRV_PID" 2>/dev/null || { echo "$arm exited during startup:" >&2; tail -20 "$LOG" >&2; return 1; }
    port_in_use && return 0
    sleep 0.2
  done
  echo "$arm did not listen on :$PORT within 30s" >&2
  return 1
}

mkdir -p "$(dirname "$OUT")"
python3 - "$OUT" "$DURATION" "$REPEATS" "$PAYLOAD" <<'PY'
import json, os, platform, subprocess, sys
def sh(cmd):
    try: return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=20).stdout.strip()
    except Exception: return ""
env = {
    "env": True,
    "date_utc": sh("date -u +%Y-%m-%dT%H:%M:%SZ"),
    "commit": sh("git rev-parse --short HEAD") + ("-dirty" if sh("git status --porcelain") else ""),
    "os": platform.platform(),
    "cpu": sh("sysctl -n machdep.cpu.brand_string") or platform.processor(),
    "cores": os.cpu_count(),
    "java": sh("java -version 2>&1 | head -1"),
    "go": sh("go version"),
    "duration": sys.argv[2], "repeats": int(sys.argv[3]), "payload": int(sys.argv[4]),
}
open(sys.argv[1], "a").write(json.dumps(env) + "\n")
PY

# One-minute load average. A busy machine makes every number here meaningless, so it is recorded
# with each group and a run on a loaded machine is refused unless ALLOW_LOAD=1.
load_now() { uptime | sed -E 's/.*load averages?: *([0-9.]+).*/\1/'; }
MAX_LOAD="${MAX_LOAD:-4.0}"
check_load() {
  local l; l="$(load_now)"
  if [ "${ALLOW_LOAD:-0}" != "1" ] && python3 -c "import sys; sys.exit(0 if float('$l') > float('$MAX_LOAD') else 1)"; then
    echo "load average is $l (limit $MAX_LOAD): something else is using this machine." >&2
    echo "Wait for it to finish, or set ALLOW_LOAD=1 to measure anyway (the numbers will not be comparable)." >&2
    exit 3
  fi
}

for arm in "${ARMS[@]}"; do
  for mode in "${MODES[@]}"; do
    flag=""; [ "$mode" = "stateless" ] && flag="stateless"
    check_load
    if port_in_use; then echo "port $PORT is already in use — refusing to measure an unknown server" >&2; exit 2; fi
    echo "→ $arm / $mode" >&2
    start_server "$arm" "$flag" || exit 1
    # Warm-up doubles as the preflight: mcpbench exits non-zero when replies fail its checks.
    if ! "$BENCH" -mode "$mode" -url "$URL" -payload "$PAYLOAD" -c 16 -warmup 0s -d "$WARMUP" -label warmup >/dev/null; then
      echo "$arm / $mode failed its warm-up; server log:" >&2; tail -20 "$LOG" >&2; exit 1
    fi
    for c in "${CONC[@]}"; do
      for _ in $(seq 1 "$REPEATS"); do
        kill -0 "$SRV_PID" 2>/dev/null || { echo "$arm died mid-run; server log:" >&2; tail -20 "$LOG" >&2; exit 1; }
        "$BENCH" -mode "$mode" -url "$URL" -payload "$PAYLOAD" -c "$c" -warmup 1s -d "$DURATION" -label "$arm" | tee -a "$OUT"
      done
    done
    rss_kb="$(ps -o rss= -p "$SRV_PID" | tr -d ' ' || echo 0)"
    echo "{\"arm\":\"$arm\",\"mode\":\"$mode\",\"payload\":$PAYLOAD,\"rss_mb\":$(( ${rss_kb:-0} / 1024 )),\"load_after\":$(load_now)}" | tee -a "$OUT"
    stop_server
    sleep 1
  done
done

echo >&2
python3 "$ROOT/bench/summarize.py" "$OUT"
echo >&2
echo "results: $OUT" >&2
