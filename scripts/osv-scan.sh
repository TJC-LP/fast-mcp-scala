#!/usr/bin/env bash
#
# Gate the published classpath on OSV.dev advisories.
#
#   scripts/osv-scan.sh                       resolve, then check (local use)
#   scripts/osv-scan.sh resolve               print one "group:artifact:version" per line: the
#                                             production classpath of the three published modules
#                                             (fast-mcp-scala.{jvm,js,scalaNative}.resolvedMvnDeps)
#   scripts/osv-scan.sh check <coords-file>   query OSV for those coordinates and gate on severity
#
# Why Mill's resolution rather than `cs resolve` of the published coordinates: on a pull request the
# artifacts do not exist yet (a publishLocal first would cost a full three-platform compile), while
# `resolvedMvnDeps` is the exact coursier resolution Mill puts on the classpath — same resolver, same
# eviction rules, no compilation. Every resolved jar must come from a Maven-layout repository
# (`.../maven2/<group>/<artifact>/<version>/<file>.jar`); anything else is a hard error, never a
# silently skipped coordinate. Note: `./mill show a b c` prints only a's value (b and c are parsed
# as arguments to a) — the brace wildcard is what yields all three modules keyed by task name.
#
# Gate rule (fail-closed):
#   * every advisory OSV returns for a coordinate is fetched (`GET /v1/vulns/<id>`; querybatch
#     itself carries only ids) and skipped only when it is withdrawn;
#   * severity is `database_specific.severity` (the GitHub Advisory Database label LOW / MODERATE /
#     HIGH / CRITICAL) when present, else the CVSS v3.x base score computed from `severity[].score`
#     (0.1-3.9 LOW, 4.0-6.9 MODERATE, 7.0-8.9 HIGH, 9.0-10.0 CRITICAL); an advisory with neither
#     (or with only a CVSS v4 vector, which this script does not score) is UNKNOWN;
#   * the run FAILS when any advisory is MODERATE or above, or UNKNOWN. LOW advisories are printed
#     but do not fail;
#   * ids listed in .github/osv-ignore (one per line, `#` comments allowed) are reported as IGNORED
#     and never fail — that file is the reviewable place for an advisory a maintainer has accepted.
#
# Exit codes: 0 clean (or LOW / IGNORED only); 1 gate failure; 2 tooling error (Mill, network, parse).
# Network: one POST to https://api.osv.dev/v1/querybatch plus one GET per distinct advisory id.
# Requires: bash, python3 (standard library only) and, for `resolve`, ./mill.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-all}"
IGNORE_FILE="$ROOT/.github/osv-ignore"

resolve() {
  local mill_out mill_log
  mill_out="$(mktemp -t fmcp-osv-mill-out.XXXXXX)"
  mill_log="$(mktemp -t fmcp-osv-mill-log.XXXXXX)"
  cd "$ROOT"
  # Mill's stdout is the JSON alone; its progress/ticker output goes to the log, shown only on failure.
  if ! ./mill --no-server show 'fast-mcp-scala.{jvm,js,scalaNative}.resolvedMvnDeps' >"$mill_out" 2>"$mill_log" ||
    ! python3 - "$mill_out" <<'PY'
import json, re, sys

def die(msg):
    print(msg, file=sys.stderr)
    sys.exit(2)

try:
    data = json.load(open(sys.argv[1]))
except ValueError:
    die("osv-scan: Mill printed no JSON (its log follows)")

def strings(x):
    if isinstance(x, str):
        yield x
    elif isinstance(x, dict):
        for v in x.values():
            yield from strings(v)
    elif isinstance(x, list):
        for v in x:
            yield from strings(v)

pathref = re.compile(r"^q?ref:v\d+:[0-9a-f]+:(.*)$")
layout = re.compile(r".*/maven2/(.+)/([^/]+)/([^/]+)/[^/]+\.jar$")
coords, bad = set(), []
for s in strings(data):
    m = pathref.match(s)
    path = m.group(1) if m else s
    l = layout.match(path)
    if not l:
        bad.append(path)
        continue
    coords.add(f"{l.group(1).replace('/', '.')}:{l.group(2)}:{l.group(3)}")
if bad:
    die("osv-scan: classpath entries outside a Maven-layout repository (extend the parser "
        "or fix the repository):\n  " + "\n  ".join(bad))
if len(coords) < 10:
    die(f"osv-scan: only {len(coords)} coordinates resolved; refusing to gate on a "
        "suspiciously small classpath")
print("\n".join(sorted(coords)))
PY
  then
    cat "$mill_log" >&2
    rm -f "$mill_out" "$mill_log"
    exit 2
  fi
  rm -f "$mill_out" "$mill_log"
}

check() {
  local coords_file="$1"
  [ -f "$coords_file" ] || { echo "osv-scan: no such coordinates file: $coords_file" >&2; exit 2; }
  python3 - "$coords_file" "$IGNORE_FILE" <<'PY'
import json, math, os, sys, time, urllib.error, urllib.request

coords_file, ignore_file = sys.argv[1], sys.argv[2]
API = "https://api.osv.dev/v1"

def die(msg):
    print(msg, file=sys.stderr)
    sys.exit(2)

LEVELS = {"NONE": 0, "LOW": 1, "MODERATE": 2, "HIGH": 3, "CRITICAL": 4}
FAIL_AT = LEVELS["MODERATE"]
ANNOTATE = os.environ.get("GITHUB_ACTIONS") == "true"

coords = [l.strip() for l in open(coords_file) if l.strip() and not l.startswith("#")]
if not coords:
    die("osv-scan: coordinates file is empty")
ignored = {}
if os.path.exists(ignore_file):
    for line in open(ignore_file):
        body = line.split("#", 1)[0].strip()
        if body:
            ignored[body] = line.strip()

def http(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Content-Type": "application/json", "User-Agent": "fast-mcp-scala/osv-scan"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            if attempt == 3:
                die(f"osv-scan: {method} {url} failed: {e}")
            time.sleep(2 ** attempt)

# --- querybatch (ids only), following per-query page tokens ---------------------------------------
queries = []
for c in coords:
    g, a, v = c.split(":", 2)
    queries.append({"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v})
hits = {}  # advisory id -> sorted list of coordinates it affects
pending, page_tokens = list(range(len(queries))), {}
while pending:
    batch = []
    for i in pending:
        q = dict(queries[i])
        if i in page_tokens:
            q["page_token"] = page_tokens[i]
        batch.append(q)
    results = http("POST", f"{API}/querybatch", {"queries": batch}).get("results")
    if not isinstance(results, list) or len(results) != len(batch):
        die(f"osv-scan: querybatch answered {len(results or [])} results for {len(batch)} queries")
    next_pending = []
    for i, r in zip(pending, results):
        for v in r.get("vulns") or []:
            hits.setdefault(v["id"], set()).add(coords[i])
        if r.get("next_page_token"):
            page_tokens[i] = r["next_page_token"]
            next_pending.append(i)
    pending = next_pending

# --- severity ---------------------------------------------------------------------------------------
def cvss3_base(vector):
    """CVSS v3.0/3.1 base score from a vector string (spec section 7.1)."""
    p = dict(part.split(":", 1) for part in vector.split("/")[1:])
    av = {"N": 0.85, "A": 0.62, "L": 0.55, "P": 0.2}[p["AV"]]
    ac = {"L": 0.77, "H": 0.44}[p["AC"]]
    ui = {"N": 0.85, "R": 0.62}[p["UI"]]
    changed = p["S"] == "C"
    pr = {"N": 0.85, "L": 0.68 if changed else 0.62, "H": 0.5 if changed else 0.27}[p["PR"]]
    cia = {"H": 0.56, "L": 0.22, "N": 0.0}
    iss = 1 - (1 - cia[p["C"]]) * (1 - cia[p["I"]]) * (1 - cia[p["A"]])
    impact = 7.52 * (iss - 0.029) - 3.25 * (iss - 0.02) ** 15 if changed else 6.42 * iss
    if impact <= 0:
        return 0.0
    exploitability = 8.22 * av * ac * pr * ui
    raw = min((1.08 if changed else 1.0) * (impact + exploitability), 10.0)
    i = round(raw * 100000)  # CVSS v3.1 Roundup
    return i / 100000.0 if i % 10000 == 0 else (math.floor(i / 10000) + 1) / 10.0

def label_for(score):
    if score == 0.0: return "NONE"
    if score < 4.0: return "LOW"
    if score < 7.0: return "MODERATE"
    if score < 9.0: return "HIGH"
    return "CRITICAL"

def severity_of(vuln):
    label = str((vuln.get("database_specific") or {}).get("severity") or "").upper()
    if label == "MEDIUM":
        label = "MODERATE"
    if label in LEVELS:
        return label, "ghsa"
    for s in vuln.get("severity") or []:
        if s.get("type") == "CVSS_V3":
            try:
                return label_for(cvss3_base(s["score"])), "cvss3"
            except (KeyError, ValueError):
                pass
    return "UNKNOWN", "none"

def fixed_versions(vuln, names):
    fixed = []
    for a in vuln.get("affected") or []:
        pkg = a.get("package") or {}
        if pkg.get("ecosystem") == "Maven" and pkg.get("name") in names:
            for rng in a.get("ranges") or []:
                for ev in rng.get("events") or []:
                    if "fixed" in ev and ev["fixed"] not in fixed:
                        fixed.append(ev["fixed"])
    return fixed

rows, failing, low, skipped, used_ignores = [], [], [], [], set()
for vid in sorted(hits):
    vuln = http("GET", f"{API}/vulns/{vid}")
    affected = sorted(hits[vid])
    if vuln.get("withdrawn"):
        skipped.append(vid)
        continue
    level, source = severity_of(vuln)
    names = {c.rsplit(":", 1)[0] for c in affected}
    aliases = ", ".join(vuln.get("aliases") or [])
    fixed = ", ".join(fixed_versions(vuln, names)) or "?"
    status = "IGNORED" if vid in ignored else ("FAIL" if level == "UNKNOWN" or LEVELS[level] >= FAIL_AT else "low")
    if vid in ignored:
        used_ignores.add(vid)
    elif status == "FAIL":
        failing.append(vid)
    else:
        low.append(vid)
    rows.append((status, level, source, vid, aliases, ", ".join(affected), fixed,
                 (vuln.get("summary") or "").strip()))

for status, level, source, vid, aliases, affected, fixed, summary in rows:
    print(f"{status:7} {level:8} [{source}] {vid}{' (' + aliases + ')' if aliases else ''}")
    print(f"        affects {affected}")
    print(f"        fixed in {fixed}  — {summary}")
    if ANNOTATE and status == "FAIL":
        print(f"::error title=OSV {level} {vid}::{affected}: {summary} (fixed in {fixed})")
for vid, entry in ignored.items():
    if vid not in used_ignores:
        print(f"note    stale ignore entry (matches no advisory): {entry}")

print(f"osv-scan: {len(coords)} coordinates, {len(hits)} advisories: {len(failing)} failing "
      f"(>= MODERATE or unknown), {len(low)} low, {len(used_ignores)} ignored, {len(skipped)} withdrawn")
sys.exit(1 if failing else 0)
PY
}

case "$MODE" in
  resolve) resolve ;;
  check)   [ "$#" -ge 2 ] || { echo "usage: $0 check <coords-file>" >&2; exit 2; }; check "$2" ;;
  all)     tmp="$(mktemp -t fmcp-osv-coords.XXXXXX)"; trap 'rm -f "$tmp"' EXIT
           resolve > "$tmp"; echo "osv-scan: $(wc -l < "$tmp" | tr -d ' ') coordinates resolved" >&2
           check "$tmp" ;;
  *)       echo "usage: $0 [resolve | check <coords-file>]" >&2; exit 2 ;;
esac
