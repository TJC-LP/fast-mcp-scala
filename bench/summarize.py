#!/usr/bin/env python3
"""Summarise bench/run.sh results (JSON lines) as Markdown tables.

    bench/summarize.py results.jsonl [baseline.jsonl]

One table per (mode, payload): a row per worker count, a column per arm with the median requests
per second over the repeats, the spread (min to max) and the median p99 latency. With a baseline
file, each cell also shows the ratio of its median to the baseline's median for the same cell.
Cells with failed requests are marked; nothing is dropped silently.
"""
import collections
import json
import statistics
import sys

MODE_ORDER = ["modern", "legacy", "stateless"]
MODE_TITLE = {
    "modern": "2026-07-28 (one request per POST)",
    "legacy": "2025-11-25 session",
    "stateless": "2025-11-25, `stateless = true`",
}


def load(path):
    runs = collections.defaultdict(list)
    rss, env, loads = {}, None, []
    for line in open(path):
        line = line.strip()
        if not line.startswith("{"):
            continue
        row = json.loads(line)
        if row.get("env"):
            env = row
        elif "rps" in row and "mode" in row:
            runs[(row["mode"], row.get("payload", 0), row["arm"], row["c"])].append(row)
        elif "rss_mb" in row and "mode" in row:
            rss[(row["mode"], row.get("payload", 0), row["arm"])] = row["rss_mb"]
            loads.append(row.get("load_after", 0))
    return runs, rss, env, loads


def median_rps(rows):
    return statistics.median(r["rps"] for r in rows)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    runs, rss, env, loads = load(sys.argv[1])
    base = load(sys.argv[2])[0] if len(sys.argv) > 2 else {}
    if env:
        print(
            f"Commit `{env['commit']}`, {env['date_utc']}; {env['cpu']}, {env['cores']} cores, "
            f"{env['os']}; {env['java']}; {env['repeats']} runs of {env['duration']} per cell.\n"
        )
    if loads and max(loads) > 4:
        print(
            f"**The machine was busy during this run (load average up to {max(loads):.1f}); "
            "do not compare these numbers with another run.**\n"
        )
    arms = []
    for (_, _, arm, _) in runs:
        if arm not in arms:
            arms.append(arm)
    groups = sorted(
        {(mode, payload) for (mode, payload, _, _) in runs},
        key=lambda g: (g[1], MODE_ORDER.index(g[0]) if g[0] in MODE_ORDER else 99),
    )
    for mode, payload in groups:
        what = "`add`" if payload == 0 else f"`echo`, {payload} bytes each way"
        print(f"### {MODE_TITLE.get(mode, mode)}: {what}\n")
        print("| Workers | " + " | ".join(arms) + " |")
        print("|---|" + "---|" * len(arms))
        workers = sorted({c for (m, p, _, c) in runs if (m, p) == (mode, payload)})
        for c in workers:
            cells = []
            for arm in arms:
                rows = runs.get((mode, payload, arm, c))
                if not rows:
                    cells.append("")
                    continue
                med = median_rps(rows)
                lo, hi = min(r["rps"] for r in rows), max(r["rps"] for r in rows)
                p99 = statistics.median(r["p99_ms"] for r in rows)
                spread = f" ({lo:,.0f} to {hi:,.0f})" if len(rows) > 1 else ""
                cell = f"{med:,.0f} rps{spread}, p99 {p99:.1f} ms"
                before = base.get((mode, payload, arm, c))
                if before:
                    cell += f", {med / median_rps(before):.2f}x baseline"
                errors = sum(r["errors"] for r in rows)
                slow = sum(r["over_500ms"] for r in rows)
                if errors:
                    cell += f", **{errors} failed requests**"
                if slow:
                    cell += f", {slow} over 500 ms"
                cells.append(cell)
            print(f"| {c} | " + " | ".join(cells) + " |")
        memory = [f"{arm} {rss[(mode, payload, arm)]} MB" for arm in arms if (mode, payload, arm) in rss]
        if memory:
            print("\nResident memory after the run: " + ", ".join(memory) + ".")
        print()


if __name__ == "__main__":
    main()
