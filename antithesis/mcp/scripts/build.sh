#!/usr/bin/env bash
# Builds the workload's images: stages the server's jars and links its Bun bundle with Mill, then
# builds every image in the compose file.
#
#   antithesis/mcp/scripts/build.sh [extra compose files...]
#
# CONTAINER_ENGINE picks the engine (default podman).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(cd "${here}/../.." && pwd)"
engine="${CONTAINER_ENGINE:-podman}"

# Runs a Mill `stage` task and prints its output directory. Mill's own output is shown only when
# the task fails. After macro edits an incremental build can fail with `NoClassDefFoundError` or
# "Cyclic macro dependencies": remove out/fast-mcp-scala and run again.
stage() {
  local task="$1" log out
  log="$(mktemp)"
  if ! out="$(cd "${root}" && ./mill --no-server show "${task}" 2>"${log}")"; then
    cat "${log}" >&2; rm -f "${log}"
    echo "build.sh: ./mill ${task} failed (stale macro build? rm -rf out/fast-mcp-scala)" >&2
    exit 1
  fi
  rm -f "${log}"
  python3 -c "import sys,json,re; print(re.sub(r'^q?ref:v\\d+:[0-9a-f]+:','',json.loads(sys.argv[1])),end='')" "${out}"
}

echo "build.sh: staging the server's jars (./mill fast-mcp-scala.antithesisSut.stage)" >&2
staged="$(stage fast-mcp-scala.antithesisSut.stage)"
[ -d "${staged}/bin" ] || { echo "build.sh: the stage task produced nothing at '${staged}'" >&2; exit 1; }
rm -rf "${here}/sut/stage"
mkdir -p "${here}/sut/stage"
cp -R "${staged}/bin" "${staged}/lib" "${here}/sut/stage/"

echo "build.sh: linking the Bun bundle (./mill fast-mcp-scala.antithesisSutJs.stage)" >&2
js="$(stage fast-mcp-scala.antithesisSutJs.stage)"
[ -f "${js}/main.js.map" ] || { echo "build.sh: the Bun stage task produced nothing at '${js}'" >&2; exit 1; }
mkdir -p "${here}/sut/stage/js"
cp "${js}/main.js" "${js}/main.js.map" "${here}/sut/stage/js/"

files=(-f "${here}/config/docker-compose.yaml")
for extra in "$@"; do files+=(-f "${extra}"); done
"${engine}" compose "${files[@]}" build
