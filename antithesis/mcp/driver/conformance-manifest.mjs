// Prints, as JSON, what the driver needs to know about the pinned conformance harness:
//
//   harness                     the installed version
//   requirements.<revision>     the scenarios the harness's own requirement set scores, and the
//                               ones it runs without scoring (with its reason for each)
//   baselines.jvm, baselines.js the repository's expected-failure entries (conformance/baseline-*.yml)
//
// Run once while the driver image is built, in the directory the harness was installed into. It
// reads YAML with the `yaml` package the harness itself depends on, so the driver (Rust) needs no
// YAML parser and reads the same files the harness does.
import { readFileSync } from "node:fs";
import { parse } from "yaml";

const pkg = "node_modules/@modelcontextprotocol/conformance";
const revision = "2026-07-28";

const fail = (why) => {
  console.error(`conformance-manifest: ${why}`);
  process.exit(1);
};

const requirements = parse(readFileSync(`${pkg}/requirements/${revision}.yaml`, "utf8"));
const scored = requirements?.server;
if (!Array.isArray(scored) || scored.length === 0 || scored.some((s) => typeof s !== "string")) {
  fail(`requirements/${revision}.yaml lists no server scenarios`);
}
const notScored = {};
for (const entry of requirements.not_scored ?? []) {
  if (entry.leg === "server") notScored[entry.scenario] = entry.reason;
}

// An entry is '<scenario>' or '<scenario>:<check-id>'; the harness rejects anything else.
const baseline = (file) => {
  const entries = (parse(readFileSync(file, "utf8")) ?? {}).server ?? [];
  if (!Array.isArray(entries) || entries.some((e) => typeof e !== "string" || e === "")) {
    fail(`${file}: every 'server' entry must be '<scenario>' or '<scenario>:<check-id>'`);
  }
  return entries;
};

console.log(
  JSON.stringify(
    {
      harness: JSON.parse(readFileSync(`${pkg}/package.json`, "utf8")).version,
      requirements: { [revision]: { scored, not_scored: notScored } },
      baselines: { jvm: baseline("baseline-jvm.yml"), js: baseline("baseline-js.yml") },
    },
    null,
    2,
  ),
);
