#!/usr/bin/env bash
# Builds the workload's images: stages the server's jars with Mill, then builds every image in
# the compose file.
#
#   antithesis/mcp/scripts/build.sh [extra compose files...]
#
# CONTAINER_ENGINE picks the engine (default podman).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root="$(cd "${here}/../.." && pwd)"
engine="${CONTAINER_ENGINE:-podman}"

echo "build.sh: staging the server's jars (./mill fast-mcp-scala.antithesisSut.stage)" >&2
staged="$(cd "${root}" && ./mill --no-server show fast-mcp-scala.antithesisSut.stage 2>/dev/null |
  python3 -c "import sys,json,re; print(re.sub(r'^q?ref:v\\d+:[0-9a-f]+:','',json.load(sys.stdin)),end='')")"
[ -d "${staged}/bin" ] || { echo "build.sh: the stage task produced nothing at '${staged}'" >&2; exit 1; }
rm -rf "${here}/sut/stage"
mkdir -p "${here}/sut/stage"
cp -R "${staged}/bin" "${staged}/lib" "${here}/sut/stage/"

echo "build.sh: linking the Bun bundle (./mill fast-mcp-scala.antithesisSutJs.stage)" >&2
bundle="$(cd "${root}" && ./mill --no-server show fast-mcp-scala.antithesisSutJs.stage 2>/dev/null |
  python3 -c "import sys,json,re; print(re.sub(r'^q?ref:v\\d+:[0-9a-f]+:','',json.load(sys.stdin)),end='')")"
[ -f "${bundle}/main.js" ] || { echo "build.sh: the Bun stage task produced nothing at '${bundle}'" >&2; exit 1; }
mkdir -p "${here}/sut/stage/js"
cp "${bundle}/main.js" "${here}/sut/stage/js/main.js"

echo "build.sh: linking the CommonJS Bun bundle (./mill fast-mcp-scala.antithesisSutCjs.stage)" >&2
cjs="$(cd "${root}" && ./mill --no-server show fast-mcp-scala.antithesisSutCjs.stage 2>/dev/null |
  python3 -c "import sys,json,re; print(re.sub(r'^q?ref:v\\d+:[0-9a-f]+:','',json.load(sys.stdin)),end='')")"
[ -f "${cjs}/main.js" ] || { echo "build.sh: the CommonJS stage task produced nothing at '${cjs}'" >&2; exit 1; }
mkdir -p "${here}/sut/stage/cjs"
cp "${cjs}/main.js" "${cjs}/main.js.map" "${here}/sut/stage/cjs/"

files=(-f "${here}/config/docker-compose.yaml")
for extra in "$@"; do files+=(-f "${extra}"); done
"${engine}" compose "${files[@]}" build
