# Skills extension — conformance, pinned sources and test matrix

← [README](../README.md) · [Skills guide](./skills.md) · [Spec coverage](./spec-coverage.md)

This page records exactly which revisions of the Skills extension and its neighbours the
implementation in this repository follows, maps every server-side requirement to the test that
locks it, lists the upstream conformance scenarios that were run, and states the discrepancies and
limitations found. It is the evidence behind the ✅ in [spec-coverage.md](./spec-coverage.md).

## Pinned sources

| Source | Revision used | Notes |
|---|---|---|
| Target repository starting point | `TJC-LP/fast-mcp-scala` commit `60e02e2` (merge of #122, `1.0.1-SNAPSHOT`) | the `main` this branch was cut from |
| **Current normative extension spec** — `modelcontextprotocol/ext-skills`, `specification/stable/skills.mdx` | repository commit **`d866efdba298b55b8156c7b7aa1bdebc1b625f4c`** (2026-09-10, "skills.mdx: CacheableResult on list/get, state 2026-07-28 baseline, record decisions (#139)"); Git blob SHA of the file **`e65b881c7440a7720acedbac000113d4946be08f`** | retrieved 2026-09-15 from `raw.githubusercontent.com`; the blob SHA equals the one recorded when the task was prepared, so the implemented text is the observed text |
| Historical SEP (design rationale) — `modelcontextprotocol/modelcontextprotocol`, `docs/seps/2640-skills-extension.mdx` | `main` as of 2026-09-15 | consulted for rationale only; where it differs from the stable page the stable page wins (see [Differences](#differences-between-sources)) |
| Agent Skills specification — `agentskills.io/specification` | `agentskills/agentskills` `docs/specification.mdx`, `main` as of 2026-09-15 (`agentskills.io` itself was not reachable through this session's egress proxy; the same page was fetched from the source repository) | `name` 1–64 `[a-z0-9-]`, no leading/trailing/consecutive hyphen, matches parent directory; `description` 1–1024; `compatibility` ≤ 500; `metadata` string → string; `allowed-tools` string (experimental) |
| Base protocol — MCP 2026-07-28 | as already implemented by this repository (`core/wire`, `WireMapping.completeResult`) | `Result.resultType`, `CacheableResult.ttlMs/cacheScope`, `PaginatedResult.nextCursor`, `Resource`, `Cursor` |
| Upstream conformance — `modelcontextprotocol/conformance` | `main` commit **`7169291ec0b68eb370fddcd9947313ab0d5e4156`** (2026-09-11, "feat(sep-2640): skills server conformance against the Accepted SEP, enumeration + manifest + directory (#330)"); scenarios `src/scenarios/server/skills/{enumeration,manifest,directory,helpers}.ts`, requirements `src/seps/sep-2640.yaml` | PR #330 is merged; the published npm `0.2.0-alpha.11` this repository pins in `conformance/package.json` (released 2026-08-07) predates it and has **no** skills scenarios, so the scenarios were run from the source checkout (see below) |
| Threat model — `ext-skills/docs/threat-model.md` | same repository commit `d866efdb` | host-side; informs the "host responsibilities" section of `docs/skills.md` and the security tests |
| scala-yaml | `org.virtuslab::scala-yaml:0.3.3` (JVM `_3`, `_sjs1_3`, `_native0.5_3`) | new production dependency, see [Dependencies](#dependencies) |

## Differences between sources

| Topic | Historical SEP | Stable page (implemented) |
|---|---|---|
| `skills/get` cache attributes | left open ("whether the result should also carry `ttlMs`/`cacheScope` … is left open") | `GetSkillResult extends CacheableResult`: both **REQUIRED** at 2026-07-28 — implemented |
| `skills/list` cache attributes | "in protocol versions 2026-07-28 and later" | same, stated as REQUIRED — implemented; stripped for pre-2026 sessions like every other list |
| Base revision | written against a draft | "against base protocol revision 2026-07-28 or later" — the 2026 path is the primary target; the initialize/session adapter serves the same methods without the 2026-only fields |
| `resources` capability | not stated | a declaring server MUST also declare `resources` — implemented (an enabled empty catalog still declares `resources`) |
| Conformance harness | extracted against the 2026-08-21 SEP branch | the `sep-2640-skills-list-cache-attributes` check already keys on the negotiated version (SKIP below 2026-07-28, FAIL when missing at 2026-07-28), matching the stable page |

The threat model still describes archive delivery (deferred in the SEP) and the `dangerous-skills-mcp`
fixture corpus; neither is part of the stable page and neither is implemented.

## Requirement → test matrix

Server MUSTs and SDK responsibilities (the stable page's normative text; § refers to its headings).
Host-only obligations are listed at the end for completeness and are documented, not enforced.

| # | Requirement (§) | Kind | Test(s) |
|---|---|---|---|
| 1 | Declare `io.modelcontextprotocol/skills` under `capabilities.extensions`, settings inline; `{}` = no optional features (§Capability Negotiation) | server MUST | `SkillsRouterTest` "legacy initialize declares…", "directoryRead off…", `SkillsStdioTranscriptTest`, `SkillsHttpTransportTest`, `SkillsJsTest`, `SkillsNativeTest`, `SkillsConformanceTest` |
| 2 | A declaring server implements `skills/list` and `skills/get` | server MUST | `SkillsRouterTest`, every transcript test |
| 3 | `directoryRead: true` ⇒ implements `resources/directory/read`; never advertised otherwise | server MUST | `SkillsRouterTest` "directoryRead off (by setting or by a provider…)" |
| 4 | A declaring server also declares `resources` | server MUST | `SkillsRouterTest` "enabled with an empty catalog…" |
| 5 | Every skill has `SKILL.md` at its root with frontmatter carrying `name` and `description` (§Skill Format) | server MUST | `SkillFrontmatterReaderTest`, `McpSkillSnapshotTest`, `SkillDirectoryLoaderTest` "no SKILL.md" |
| 6 | URI form `<scheme>://<skill-path>/<file-path>`; final segment = `frontmatter.name`, naming rules; authority is a reg-name, never resolved (§Resource Mapping) | server MUST / SHOULD | `SkillUriTest`, `McpSkillSnapshotTest` "final path segment…", `SkillVerifierTest` validateEntry |
| 7 | Other schemes allowed, same structural rules | server MAY | `SkillUriTest`, `McpSkillSnapshotTest` (`github://`), `SkillDirectoryLoaderTest` |
| 8 | `SKILL.md` resource metadata: `mimeType text/markdown`, `name`/`description` from frontmatter (§Resource Metadata) | server SHOULD | `SkillsRouterTest` "skill files are ordinary resources…", `McpSkillSnapshotTest` |
| 9 | Nested skills: nested content is supporting content; publication is flat; a nested entry shares the prefix (§Nested Skills) | server rules | `McpSkillSnapshotTest` "nestedSkill…", `SkillRegistryTest` "duplicate names…", `SkillsServerExampleTest` |
| 10 | Entry shape `{uri, frontmatter, resources}`; frontmatter verbatim, unknown fields preserved (§Skill Entries, §Frontmatter) | server MUST | `SkillsWireCodecTest`, `SkillFrontmatterReaderTest` "unknown fields…" |
| 11 | `frontmatter` identical in content to the served `SKILL.md` | server MUST | `SkillsRouterTest` "the advertised digests are what an independent verifier computes…", `SkillsServerExampleTest` (dynamic), `SkillsConformanceTest` |
| 12 | Names are labels; duplicates across paths are legal (§Names) | server rule | `SkillRegistryTest` "duplicate names at different paths are legitimate…" |
| 13 | `resources` REQUIRED: complete array (each file once, `SKILL.md` included, all within the skill, `digest` + `size`) or `"dynamic"`; anything else invalid (§Resources) | server MUST | `SkillsWireCodecTest` (invalid union forms, size/digest validation), `McpSkillSnapshotTest`, `SkillVerifierTest` validateEntry |
| 14 | Dynamic content ⇒ `"dynamic"`, no fake digests | server MUST | `SkillsRouterTest` (provider), `SkillRegistryTest` providers, `SkillsServerExampleTest` |
| 15 | Limits 512 entries / 16 MiB (§Limits) | server SHOULD NOT exceed; SDK limit | `McpSkillSnapshotTest` "limits at the exact boundaries…", `SkillRegistryTest` "a failing batch…", `SkillVerifierTest` |
| 16 | `skills/list`: paginated, may be empty/partial, entries atomic, `resultType: "complete"`, `ttlMs`, `cacheScope` (§Listing Skills) | server MUST | `SkillsRouterTest` "skills/list: legacy strips…", `SkillRegistryTest` "pagination…", `SkillsHttpTransportTest` |
| 17 | `skills/get`: `{skill}`, `resultType: "complete"`, cache attributes, no cursor; unknown → `-32602`; answers for unlisted skills (§Getting a Skill) | server MUST | `SkillsRouterTest` "skills/get: listed, unlisted and dynamic…", `SkillRegistryTest` "unlisted skills…" |
| 18 | Skill files read via ordinary `resources/read`; unknown file → `-32602` (2026) (§Reading Skill Content, §Error Handling) | server MUST | `SkillsRouterTest` "skill files are ordinary resources…", transcripts |
| 19 | Relative references resolve against the skill root, `..` never escapes (§Relative References) | SDK helper | `SkillUriTest` "relative references…" |
| 20 | Digests: SHA-256 of raw bytes, `sha256:` + 64 lowercase hex (§Integrity and Verification) | server MUST | `Sha256AndUtf8Test` (NIST vectors + `MessageDigest`), `McpSkillSnapshotTest`, `SkillsRouterTest` (blob decoded bytes) |
| 21 | Directory resources: `inode/directory`, no trailing slash, every level, addressable whether listed or not (§Directory Resources) | server MUST | `McpSkillSnapshotTest` "the directory index…", `SkillsRouterTest` "resources/directory/read…" |
| 22 | `resources/directory/read`: direct children only, files + subdirectories, empty → `[]`, paginated, `resultType: "complete"`, unknown/non-directory → `-32602`, every directory of the served namespaces (§Reading Directories) | server MUST | `SkillsRouterTest`, `SkillRegistryTest` "pagination…" (directory pages), transcripts on all platforms |
| 23 | Errors: `-32602` unknown skill / bad directory, `-32601` when not declared, ids preserved, malformed requests do not end a session (§Error Handling) | server MUST | `SkillsRouterTest` "disabled…", `SkillsStdioTranscriptTest` "malformed skills requests never terminate…" |
| 24 | Reserved `_meta` prefix, reserved `metadata` keys (§Reservations) | server SHOULD | the server emits no `_meta` on skill resources; `SkillVerifier`/frontmatter reader preserve `metadata` verbatim (`SkillFrontmatterReaderTest`) |
| 25 | Cancellation: an in-flight `skills/list` is interruptible, no stray reply | SDK | `SkillsStdioTranscriptTest` "notifications/cancelled…" |
| 26 | Atomic publication / replacement / removal; no half-published generation | SDK | `SkillRegistryTest` "replace is atomic…", "a failing batch…", "concurrent republishing…" |
| 27 | Conflicting resource ownership refused (static, template, provider, nested mismatch, file-vs-directory) | SDK | `SkillRegistryTest`, `SkillsRouterTest` "a static resource or template colliding…" |
| 28 | Metadata discovery reads no supporting file | SDK | `SkillRegistryTest` "providers…" (read counter) |
| 29 | URI policy: encoded traversal, sibling prefixes, aliases, backslashes, NUL, Unicode (§Resource Mapping + threat model T5) | SDK | `SkillUriTest`, `SkillsRouterTest` "skills/get…" error table |
| 30 | Frontmatter parser safety: duplicate keys, tags, anchors/aliases, depth/size/node bombs, strict UTF-8, BOM, CRLF | SDK | `SkillFrontmatterReaderTest`, `Sha256AndUtf8Test` |
| 31 | Filesystem loading: symlinks/special files refused, containment, bounded reads, explicit selection (threat model T5/T6) | SDK (JVM) | `SkillDirectoryLoaderTest` |
| 32 | Host verification helpers: size/digest/frontmatter verification, unlisted → failure, dynamic → unverifiable, identity = (label, uri), content-bound approval key | SDK helpers | `SkillVerifierTest` |
| 33 | Root import exposes every documented name | SDK | `SkillsRootImportTest` |

Host-only obligations (documented in `docs/skills.md` § Host responsibilities, not enforceable by a
server): lazy retrieval, verification on read and frontmatter comparison before load, acting
window / held entry, content-bound approval and revocation, per-origin name namespaces, origin
tagging and origin-scoped reads, no implicit local execution, `allowed-tools` gating, nested-skill
consent, cache integrity/isolation, treating directory reads as observation rather than manifest
extension. `SkillVerifier` and `HeldEntry` give a host the pure pieces; they never load, refresh or
approve anything.

## Platform / protocol / transport matrix (what was executed)

| Platform | Transport | Protocol | Test |
|---|---|---|---|
| JVM | in-process router | 2025-11-25 + 2026-07-28 | `SkillsRouterTest` |
| JVM | real `StdioLoop` (the stdio transport's dispatch loop over in-memory streams) | 2025-11-25 handshake and 2026-07-28 stateless | `SkillsStdioTranscriptTest` |
| JVM | zio-http routes (stateless 2026-07-28 POSTs with `Mcp-Protocol-Version`/`Mcp-Method`/`Mcp-Name`; legacy initialize/session) | both | `SkillsHttpTransportTest` |
| JVM server ⇐ **official TypeScript SDK client** (`@modelcontextprotocol/sdk` 1.29.0) over a real stdio pipe | stdio | 2025-11-25 (what the SDK negotiates) | `js.test` `SkillsConformanceTest` |
| Scala.js / Bun | production Node stdin callbacks (`JsTransportBackend.wireStdin`) | 2025-11-25 | `SkillsJsTest` |
| Scala.js / Bun | `Bun.serve` stateless HTTP | 2026-07-28 | `SkillsJsTest` |
| Scala Native | real `StdioLoop` on the Native runtime | 2025-11-25 | `SkillsNativeTest` |
| JVM HTTP fixture ⇐ **upstream conformance scenarios** `sep-2640-skills-*` (conformance `main` 7169291, run from source) | HTTP | as negotiated by the harness | see next section |

Commands (this checkout, Mill 1.1.8; the JVM launcher was used because the native Mill launcher
cannot trust the session's egress proxy CA):

```bash
MILL_VERSION=1.1.8-jvm ./mill fast-mcp-scala.jvm.test        # full JVM suite
MILL_VERSION=1.1.8-jvm ./mill fast-mcp-scala.js.test         # Scala.js/Bun suite
MILL_VERSION=1.1.8-jvm ./mill fast-mcp-scala.scalaNative.test
MILL_VERSION=1.1.8-jvm ./mill fast-mcp-scala.checkFormat
```

Results are recorded in the pull request description for this change; the one Bun failure
observed in the development container (`JsServerHttpTest` "bearer tasks … bucketed per peer
address") is `EAFNOSUPPORT` on binding `::` — the container has no IPv6 — and is unrelated to
this feature.

## Upstream conformance scenarios

The pinned harness (`conformance/package.json`, `0.2.0-alpha.11`) contains no skills scenarios, so
the existing `scripts/conformance.sh` gate is untouched (73/73 active, 37/37 2026 — the skills
extension does not change any of those scenarios' inputs). The merged `#330` scenarios were run
from a source checkout of `modelcontextprotocol/conformance` at `7169291` against
`com.tjclp.fastmcp.examples.SkillsHttpServer` (`127.0.0.1:8091`):

```bash
./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.SkillsHttpServer &
# in the conformance checkout (git clone, `bun install`), one scenario per run:
bun run src/index.ts server --scenario sep-2640-skills-enumeration --url http://127.0.0.1:8091/mcp
bun run src/index.ts server --scenario sep-2640-skills-manifest    --url http://127.0.0.1:8091/mcp
bun run src/index.ts server --scenario sep-2640-skills-directory   --url http://127.0.0.1:8091/mcp
```

Scenarios and their checks, with the outcome observed on 2026-09-15 (the harness negotiated
2026-07-28, so the cache-attribute checks were scored, not skipped; raw logs are quoted in the pull
request description):

| Scenario | Checks | Outcome |
|---|---|---|
| `sep-2640-skills-enumeration` | capability inline / empty-object / requires-resources / commits-to-methods; `skills/list` implemented, pagination, entry atomic, cache attributes; entry `uri`/`frontmatter`/`resources` required, uri = name, `skill://` scheme (SHOULD), completeness, containment, digest format, size, limits, reserved metadata prefix, naming rules, authority reg-name, unique names (SHOULD); `skills/get` implemented, entry shape, cache attributes, no cursor, unknown → `-32602`; read-back: `SKILL.md` present, frontmatter parses, entry frontmatter identical | **32/32 passed, 0 failed, 0 warnings** |
| `sep-2640-skills-manifest` | `SKILL.md` mimeType, resource name/description = frontmatter, final segment = name, `_meta` prefix | **6/6 passed, 0 failed, 0 warnings** |
| `sep-2640-skills-directory` | `directoryRead` flag, method registered, result shape, subdirectory mimeType, non-directory → `-32602`, pagination | **7/7 passed, 0 failed, 0 warnings** |

The harness accepts scenario names, not globs (`--scenario sep-2640-skills-enumeration`, one run
per scenario).

Where a check is a SHOULD, the fixture is designed to satisfy it (`skill://` scheme, unique names,
`text/markdown`, no `_meta`). The scenario code's own expectation of `-32602` for unknown skills and
non-directories, the inline capability settings and the 2026-gated cache attributes all match the
stable page; no check had to be weakened and no fixture assumption contradicted the stable text.

## Discrepancies, decisions and limitations

- **Frontmatter block extraction.** The YAML text is the lines between the delimiter lines without
  the line break before the closing `---` (matching the regex `^---\r?\n([\s\S]*?)\r?\n---` the
  upstream helper and most hosts use), so a literal block scalar that ends the frontmatter has no
  trailing newline on both sides of a field-by-field comparison. Documented in `docs/skills.md`.
- **scala-yaml tokenizer escaping.** scala-yaml 0.3.3 rewrites `\` → `\\` and line breaks → `\n`
  inside plain and folded scalars at tokenization time; the reader inverts that exactly
  (`SkillFrontmatterReader.unescapeTokenizer`, tested with backslashes and multi-line plain and
  folded scalars). Complex (non-scalar) keys are rejected by the parser itself.
- **YAML features rejected on purpose.** Anchors, aliases, explicit tags and duplicate keys fail the
  skill instead of being interpreted; `.inf`/`.nan` fail because JSON cannot carry them. A skill
  author who needs those is asked to write plain data.
- **`metadata` values must be strings.** Enforced from the Agent Skills text ("a map from string
  keys to string values"); a `metadata` with a numeric value is rejected at authoring time.
- **Percent-encoding.** Never decoded (policy, see `docs/skills.md`). A client that percent-encodes
  a URI the server published raw gets `-32602`; every published URI is served exactly as listed.
- **`resources/list` and unlisted skills.** Files of unlisted skills are also absent from
  `resources/list` (consistent with the skill being unlisted); they remain readable.
- **Directory view of a shared nested root.** When a parent and a nested skill both publish a
  directory, the nested skill's view is served (its `SKILL.md` named by frontmatter); shapes must
  agree (same URIs and kinds) or publication fails.
- **Legacy `resources/read` miss** stays `-32002` on pre-2026 sessions (the base protocol's reserved
  code), `-32602` on 2026-07-28; `skills/get` and directory misses are `-32602` in both.
- **Not implemented.** `notifications/resources/list_changed` for skill changes (the server has no
  list-changed publisher yet, see spec-coverage); the pre-stable `skill://index.json`; archives
  (deferred by the SEP); any client/host runtime.
- **Environment.** The conformance run and the Bun IPv6 failure are recorded in the PR; the
  `agentskills.io` host was not reachable from the development session's egress proxy, so the
  Agent Skills page was read from its source repository.

## Dependencies

`org.virtuslab::scala-yaml:0.3.3` is the single new production dependency (JVM, Scala.js 1,
Scala Native 0.5 artifacts). Rationale under `DEPENDENCY_POLICY.md` "a new dependency feature is
needed": `SKILL.md` frontmatter is YAML, a hand-written subset would be exactly the "undocumented
YAML subset masquerading as full support" the extension warns against, and scala-yaml is the only
maintained YAML parser cross-published for all three targets with no transitive dependencies. The
reader consumes its **event** stream rather than its node API so duplicate keys, anchors, aliases
and tags can be rejected. The `scripts/osv-scan.sh` gate covers it like every other production
dependency; `.github/workflows/osv.yml` runs it on every pull request that touches `build.mill` or
`fast-mcp-scala/package.mill`, so this change triggers it. The scan could not be executed from the
development session that produced this change (`api.osv.dev` was refused by that session's egress
proxy), which is recorded as a limitation in the pull request description rather than reported as a
clean run.
