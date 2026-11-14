#!/usr/bin/env bash
# Runs the workload on this machine, with no faults, until the driver's eventually ends it; then
# lists every assertion that would fail in a run.
#
#   antithesis/mcp/scripts/local.sh [seconds of workload before the eventually]
#
# CONTAINER_ENGINE picks the engine (default podman). Evidence lands in a fresh directory under
# antithesis/mcp/out/ (git-ignored).
#
# Once the eventually's own checks are done the driver runs the official conformance harness.
# Here it runs all three suites (active, 2026-07-28 requirements, skills extension scenarios)
# against every node; DRIVER_CONFORMANCE_NODE (a node's name, or `all`) and
# DRIVER_CONFORMANCE_SUITE (`active`, `2026`, `skills`, `all`, `off`) narrow that, and setting
# either to the empty string lets the driver draw it, as on Antithesis.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
engine="${CONTAINER_ENGINE:-podman}"
export DRIVER_EVENTUALLY_AFTER_S="${1:-90}"
# The Bun node runs natively here (see local/docker-compose.local.yaml).
case "$(uname -m)" in
  arm64|aarch64) export LOCAL_PLATFORM="${LOCAL_PLATFORM:-linux/arm64}" ;;
  *) export LOCAL_PLATFORM="${LOCAL_PLATFORM:-linux/amd64}" ;;
esac
compose=("${engine}" compose -f "${here}/config/docker-compose.yaml" -f "${here}/local/docker-compose.local.yaml")
out="${here}/out/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "${out}"

"${compose[@]}" down -v >/dev/null 2>&1 || true
"${here}/scripts/build.sh" "${here}/local/docker-compose.local.yaml"
"${compose[@]}" up -d
"${compose[@]}" exec -T driver /opt/antithesis/test/v1/main/first_start_workload

# The driver exits 0 when its eventually ends the timeline. The wait covers what the eventually
# may take: 300 s for its own checks (up to 120 s for the nodes to serve again, then the session
# and stream checks on each), and 300 s for every harness run the driver plans, which is the
# deadline the driver gives one run (DEADLINE in driver/src/conformance.rs). The plan is one run
# for each node and suite named. An empty node is every node (the drawn one first); an empty suite
# is all three suites (the drawn one first).
nodes=3 # mcp-netty, mcp-socket, mcp-bun
case "${DRIVER_CONFORMANCE_NODE-all}" in all | "") node_runs=${nodes} ;; *) node_runs=1 ;; esac
case "${DRIVER_CONFORMANCE_SUITE-all}" in all | "") suite_runs=3 ;; off) suite_runs=0 ;; *) suite_runs=1 ;; esac
harness_runs=$(( node_runs * suite_runs ))
wait_s=$(( DRIVER_EVENTUALLY_AFTER_S + 300 + harness_runs * 300 ))
echo "local.sh: waiting up to ${wait_s} s for the driver (${harness_runs} harness runs planned)"
deadline=$(( $(date +%s) + wait_s ))
while [[ "$("${engine}" inspect -f '{{.State.Running}}' driver 2>/dev/null)" == "true" ]]; do
  if (( $(date +%s) > deadline )); then
    echo "local.sh: the driver is still running past its deadline" >&2
    break
  fi
  sleep 5
done
code="$("${engine}" inspect -f '{{.State.ExitCode}}' driver)"

"${compose[@]}" cp driver:/tmp/sdk.jsonl "${out}/sdk.jsonl" || true
# The conformance harness's own output and result files, one directory per suite and node.
"${compose[@]}" cp driver:/tmp/conformance "${out}/conformance" || true
for svc in mcp-netty mcp-socket mcp-bun; do
  "${compose[@]}" cp "${svc}:/tmp/sdk.jsonl" "${out}/sdk-${svc}.jsonl" || true
done
for svc in driver mcp-netty mcp-socket mcp-bun; do
  "${compose[@]}" logs --no-color "${svc}" > "${out}/${svc}.log" 2>&1 || true
done
# Down, not stop: `snouty validate` refuses a project with containers left over.
"${compose[@]}" down -v >/dev/null 2>&1

echo "local.sh: driver exit code ${code}; evidence in ${out}"
echo "local.sh: the conformance harness:"
grep -a 'driver: conformance:' "${out}/driver.log" | sed 's/^.*driver: conformance: /  /' || echo "  (it did not run)"
grep -a 'driver: wire:' "${out}/driver.log" | sed 's/^.*driver: wire: /local.sh: /' || true
echo "local.sh: assertions that would fail in a run (see known-failing.tsv for the expected ones):"
jq -rs '[.[].antithesis_assert // empty] | group_by(.message)[]
  | select(if .[0].assert_type == "sometimes" then any(.hit and .condition) | not
           else any(.hit and (.condition | not)) or (.[0].must_hit and (any(.hit) | not)) end)
  | "\(.[0].display_type)\t\(.[0].message)"' "${out}"/sdk*.jsonl | tee "${out}/unmet.tsv"
[[ "${code}" == "0" ]]
