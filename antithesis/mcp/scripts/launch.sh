#!/usr/bin/env bash
# Builds, validates and launches the workload on Antithesis.
#
#   antithesis/mcp/scripts/launch.sh <max compute hours> [extra snouty launch flags]
#
# Needs ANTITHESIS_API_KEY and ANTITHESIS_REPOSITORY in the environment (or a `snouty login`),
# and registry credentials for that repository. Check the compute budget before each launch; this
# script only passes the number on. CONTAINER_ENGINE picks the engine (default podman).
#
# Faults: the `resilient` profile. Each node is a single server with in-memory sessions and
# tasks, so it is not meant to survive being killed; `resilient` pauses and slows nodes, degrades
# the network, pauses threads and skips the clock, which is where a transport's races are.
set -euo pipefail

hours="${1:?usage: launch.sh <max compute hours> [extra snouty launch flags]}"
shift
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
engine="${CONTAINER_ENGINE:-podman}"
export SNOUTY_CONTAINER_ENGINE="${engine}"
export SNOUTY_UNSTABLE_FEATURES=teams
config="${here}/config"
plugin_version="${ANTITHESIS_PLUGIN_VERSION:-unknown}"

# `snouty validate` refuses a project with containers left from another run.
"${engine}" compose -f "${config}/docker-compose.yaml" down -v >/dev/null 2>&1 || true
"${here}/scripts/build.sh"
# `snouty validate` runs the x86-64 images on this machine. On an ARM workstation the x86-64 Bun
# binary aborts under emulation, so validation cannot pass there: SKIP_VALIDATE=1 skips it. Run
# scripts/local.sh first in that case (it runs the Bun node natively), and expect a broken
# image to show up as a setup timeout in the run.
if [[ "${SKIP_VALIDATE:-0}" == "1" ]]; then
  echo "launch.sh: skipping snouty validate (SKIP_VALIDATE=1)" >&2
else
  snouty validate "${config}"
fi
snouty launch --launcher basic_test --config "${config}" \
  --test-name fast-mcp-scala-mcp \
  --source fast-mcp-scala-mcp \
  --max-compute-hours "${hours}" \
  --fault-tolerance resilient \
  --fault-exclusions '^driver$' \
  --param attrs.workload=mcp \
  --param "attrs.sut_commit=$(git -C "${here}" rev-parse --short HEAD)" \
  --param "attrs.plugin_version=${plugin_version}" \
  "$@"
