# Skills over MCP (`io.modelcontextprotocol/skills`)

← [README](../README.md) · see also [Conformance & source revisions](./skills-conformance.md) · [Transports](./transports.md) · [Spec coverage](./spec-coverage.md)

fast-mcp-scala implements the **Skills extension** (SEP-2640, extension identifier
`io.modelcontextprotocol/skills`) as a native part of the shared MCP core, on JVM, Scala.js/Bun and
Scala Native, over every transport the platform has. A server publishes a skill once through the
normal Scala API; an extension-aware peer discovers it with `skills/list`, resolves it directly with
`skills/get`, retrieves its exact files through ordinary MCP Resources (`resources/read`), browses
its directories with `resources/directory/read`, and can independently verify the manifest the
server advertised.

The skill format itself (directory of files, `SKILL.md` with YAML frontmatter, naming rules) is the
[Agent Skills specification](https://agentskills.io/specification); the extension defines only
the MCP binding. The exact revisions this implementation follows are pinned in
[skills-conformance.md](./skills-conformance.md).

## Quick start

```scala 3 raw
import com.tjclp.fastmcp.{*, given}

object ExampleServer extends McpServerApp[Stdio, ExampleServer.type]:

  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b

  override val skills: List[McpSkill] = List(
    McpSkill.fromMarkdown(
      skillPath = "acme/reconcile-positions",
      markdown =
        """---
          |name: reconcile-positions
          |description: Reconcile broker positions against the ledger and flag breaks.
          |---
          |
          |Read `references/rules.md` first.
          |""".stripMargin,
      files = Map(
        "references/rules.md" -> SkillFile.text("# Rules\n\nTolerance: 0.01.\n"),
        "assets/example.bin" -> SkillFile.binary(Array[Byte](0, 1, 2))
      ),
      emptyDirectories = Set("templates/drafts")
    )
  )
```

That server declares `capabilities.extensions["io.modelcontextprotocol/skills"] = {"directoryRead": true}`
and the `resources` capability, and serves:

| Request | Result |
|---|---|
| `skills/list` | one entry: `uri: skill://acme/reconcile-positions/SKILL.md`, the verbatim frontmatter, and a `resources` array with a `sha256:` digest and byte `size` for `SKILL.md`, `references/rules.md` and `assets/example.bin` |
| `skills/get {uri}` | the same entry as `{"skill": ...}` |
| `resources/read` on any listed URI | `text` for the Markdown files (exact bytes, line endings preserved), a base64 `blob` for `example.bin` |
| `resources/directory/read {uri: "skill://acme/reconcile-positions"}` | the direct children: `assets`, `references`, `templates` (`inode/directory`) and `SKILL.md` |
| `resources/list` | every file of every listed skill, as ordinary resources |

`McpSkill.fromMarkdown` validates everything up front and **throws** `SkillError.Exception` on
invalid input (it is meant for `val` declarations). `McpSkill.parse(...)` returns
`Either[SkillError, McpSkill]` for programmatic construction. Both constructors take the exact
`SKILL.md` text (`parseBytes` / `fromMarkdownBytes` take bytes), so the digest describes what is
served — there is no separately maintained metadata copy.

Effectful registration on an `McpServer`:

```scala 3 raw
import com.tjclp.fastmcp.{*, given}
import zio.*

val server = McpServer("my-server")
val program: ZIO[Any, Throwable, Unit] =
  server.skills(List(skillA, skillB)) *>     // one atomic batch; nothing is published on failure
    server.skill(skillC) *>                  // add or replace (by root URI)
    server.removeSkills(List("skill://acme/old")) *>
    server.runStdio()
```

`server.skill(...)` / `skills(...)` return a ZIO that registers **on evaluation** — sequence it, like
`server.tool(...)`. A statement that discards it registers nothing.

Examples: [`SkillsServer`](../fast-mcp-scala/shared/src/com/tjclp/fastmcp/examples/SkillsServer.scala)
(in-memory skill with text, binary, nested directory, empty directory, a nested skill, an unlisted
skill and a dynamic provider, next to a `@Tool`; cross-platform),
[`SkillsHttpServer`](../fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/SkillsHttpServer.scala)
(the same catalog over HTTP; the conformance fixture) and
[`SkillDirectoryServer`](../fast-mcp-scala/jvm/src/com/tjclp/fastmcp/examples/SkillDirectoryServer.scala)
(JVM: publish an on-disk skill directory).

## Public API

All of these are reachable from the root import `com.tjclp.fastmcp.{*, given}`.

| Type | Role |
|---|---|
| `McpSkill` | A validated in-memory skill: root URI, parsed frontmatter, exact `SKILL.md` bytes, supporting files, empty directories, `listed` flag. `fromMarkdown` / `parse` / `parseBytes` / `fromMarkdownBytes`, `unlisted`, `nestedSkill(dir)`, `checkLimits(...)`. |
| `SkillFile` | `SkillFile.text(string)`, `SkillFile.textBytes(bytes)` (strict UTF-8, `Either`), `SkillFile.binary(bytes)`; optional `mimeType`. Bytes are copied on construction and on every read. |
| `SkillUri` | The one URI policy: `parse`, `fromPath`, `resolveRelative`, `isWithin`, `skillRootOf` (see [URI rules](#uri-identity-and-containment)). |
| `SkillProvider[R]` | Effectful provider for generated / remote / authorization-scoped catalogs (see [Dynamic providers](#dynamic-providers)). |
| `SkillSettings` | `McpServerSettings.skills`: `enabled`, `directoryRead`, page sizes, cache attributes, per-skill limits. |
| `Skill`, `SkillFrontmatter`, `SkillResource`, `SkillResources` | The wire entry shapes (`core.wire`), with validating codecs. `SkillResources` is `Static(List[SkillResource])` or `Dynamic`. |
| `SkillSnapshot` | The immutable publication of one skill: wire entry, files by URI, directory index. `SkillSnapshot.build(skill)` uses the portable SHA-256; the server passes the platform digest. |
| `SkillVerifier`, `HeldEntry`, `SkillIdentity`, `VerificationOutcome`, `VerificationFailure` | Host-side verification helpers (see [Verification](#verification-helpers-host-side)). |
| `SkillError` | Typed authoring/publication failures naming the skill, the file and the reason; `SkillError.Exception` is its `Throwable` form. |
| `SkillDirectoryLoader` (JVM only, `com.tjclp.fastmcp.server.skills`) | Load a directory into an `McpSkill` (see [JVM directory loading](#jvm-directory-loading)). |

`McpServerApp` gains `skills: List[McpSkill]` and `skillProviders: List[SkillProvider[Any]]`;
`McpServerCore` gains `skill`, `skills`, `removeSkills` and `skillProvider`.

## Enabling and declaring the extension

The extension is declared exactly when it is served:

- registering at least one skill or provider declares it (honest capabilities, like tools);
- `McpServerSettings(skills = SkillSettings(enabled = true))` declares it with an **empty catalog**
  (`skills/list` → `[]`, `skills/get` → `-32602`), which is legitimate and different from not
  declaring it;
- with nothing registered and `enabled = false` (the default), the extension is absent from
  `capabilities.extensions` and `skills/list`, `skills/get`, `resources/directory/read` answer
  `-32601` on every protocol revision.

A declaring server always also declares `resources` (skill files are read through
`resources/read`), even when it has no other resources. The extension object is `{"directoryRead": true}`
when `SkillSettings.directoryRead` is on (the default) **and** every registered provider supports
directory reads, `{}` otherwise; `resources/directory/read` is wired only in the first case, so the
flag is never advertised for a method that would answer `-32601`.

Other extensions are untouched: with `TaskSettings(enabled = true)` a 2026-07-28 `server/discover`
lists both `io.modelcontextprotocol/tasks` and `io.modelcontextprotocol/skills`.

### Protocol revisions

| Base revision | Declared | `skills/list` / `skills/get` | Cache attributes | `resources/read` miss |
|---|---|---|---|---|
| 2026-07-28 (stateless requests, `server/discover`) | `capabilities.extensions` in `server/discover` | yes | `resultType: "complete"`, `ttlMs`, `cacheScope` on both (REQUIRED by the stable spec) | `-32602` |
| 2025-11-25 and earlier (initialize/session adapter) | `capabilities.extensions` in the `initialize` result | yes | none (they do not exist in those schemas; the router strips them as for every list) | `-32002` (the reserved legacy code) |

`skills/get` unknown → `-32602`, `resources/directory/read` unknown/not-a-directory → `-32602`, in
both eras. Request ids are preserved on every error. The extension is never disabled because an
older base revision was negotiated. Only the current stable extension revision is implemented;
the pre-stable `skill://index.json` shape is not.

## URI identity and containment

Everything that constructs, parses, resolves or compares a skill URI goes through `SkillUri`. The
policy is literal:

- A URI is `<scheme>://<segment>/<segment>/...`. Any lowercase scheme is accepted (`skill` is the
  default and recommended one; `github://owner/repo/skills/lint/SKILL.md` works). No scheme is
  privileged: a `skill://` prefix alone does not make something a skill — only a `skills/list`
  entry or a successful `skills/get` does.
- The first segment (authority position) is a namespace component restricted to RFC 3986
  unreserved characters, lowercase. It carries no network semantics and is **never resolved**
  (no DNS, no HTTP, no filesystem).
- Later segments may contain raw Unicode (IRI-style) but not `/`, `\`, `%`, `?`, `#`, whitespace
  or control characters, and never `.` / `..`. Empty segments and trailing slashes are rejected.
- **Percent-encoding is never applied or decoded.** Matching is byte-for-byte, so no two distinct
  strings name the same resource: `%2F`, `%2E%2E`, `%25`, double encodings, mixed case in the
  authority or scheme are all simply not served (`-32602`).
- The final skill-path segment must equal `frontmatter.name` and satisfy the Agent Skills naming
  rules (1–64 chars, `[a-z0-9-]`, no leading/trailing/consecutive hyphens). Names are labels, not
  identifiers: `skill://acme/billing/refunds` and `skill://acme/support/refunds` may coexist.
- Containment is segment-aware: `skill://a/demo-other/x` is not within `skill://a/demo`.
- Relative references resolve against the skill root like filesystem paths
  (`SkillUri.resolveRelative(root, directory, "../templates/x.md")`): `.` is dropped, `..` climbs
  one directory and is refused once it would leave the skill root, absolute paths, backslashes,
  trailing slashes and empty segments are refused. Authority-only roots (`skill://example`) work.
- Filesystem validation (the JVM loader) is separate from URI validation.

Client URIs longer than `limits.maxUriChars` (8192) are rejected with `-32602` before any lookup.

## Frontmatter

`SKILL.md` is read as strict UTF-8 (malformed input is rejected, never repaired). A leading BOM is
skipped for parsing only — the served bytes, and therefore the digest and `size`, include it. Line
endings may be LF or CRLF. The file must start with a line that is exactly `---`; the block ends at
the next such line, and the YAML text is the lines in between **without** the line break before
the closing delimiter (the same extraction the regex-based readers hosts use perform).

The YAML is parsed by [scala-yaml](https://github.com/VirtusLab/scala-yaml) 0.3.3 (the one YAML
library cross-published for Scala 3 on JVM, Scala.js and Scala Native, no transitive
dependencies), driven at the **event** level so the reader can:

- reject duplicate keys (scala-yaml's node API would silently collapse them), non-scalar keys,
  anchors, aliases and every author-written tag (`!!str`, `!custom`): frontmatter is data, never a
  graph and never a type hint;
- bound the block size (64 KiB), nesting depth (32) and node count (4096) before anything is
  allocated; a 200-deep flow bomb is refused without a stack overflow;
- convert scalars with the YAML 1.2 core schema: quoted, literal (`|`) and folded (`>`) scalars are
  always strings; plain `null`/`~`/empty → `null`, `true`/`false` → booleans, decimal / `0o` /
  `0x` integers and decimal floats → numbers (arbitrary precision), `.inf`/`.nan` → rejected (no
  JSON representation), everything else → string.

Every field the author wrote is preserved verbatim in source order. The Agent Skills rules are
enforced: `name` and `description` required (1–64 / 1–1024 characters), `compatibility` 1–500
characters when present, `license` and `allowed-tools` strings when present, `metadata` a
string-to-string mapping when present. `allowed-tools` is **metadata**: the server passes it
through and grants nothing.

## Bytes, digests, limits

- Text files are served as `text` (exact UTF-8, line endings preserved); binary files as a base64
  `blob`. Digests are SHA-256 of the **raw** bytes — decoded bytes for a blob, never the base64
  text — formatted `sha256:` + 64 lowercase hex; `size` is the raw byte count.
- SHA-256 comes from the platform where one exists (`java.security.MessageDigest` on the JVM,
  through `TransportBackend.sha256`) and from a portable FIPS 180-4 transcription
  (`core.skills.Sha256`) on Scala.js and Scala Native, which have no `MessageDigest`. The portable
  implementation is pinned to the NIST example vectors on every platform and to `MessageDigest`
  over random inputs on the JVM.
- A published `SkillSnapshot` is immutable: sizes and digests are computed once from the bytes
  actually served, inputs are defensively copied, and every later listing or read is served from
  the snapshot without re-reading or re-hashing.
- Limits: the spec fixes **512 resources per skill** (`SKILL.md` included) and **16 MiB total raw
  bytes** as what every conforming host accepts. `SkillSettings.maxResourcesPerSkill` /
  `maxTotalBytesPerSkill` default to exactly those values and are checked at registration with
  checked arithmetic (`SkillError.LimitExceeded`); raise them only for hosts known to accept more.
  These are raw-byte budgets, not wire budgets: a binary file costs ~4/3 of its size as base64 in
  one `resources/read` frame, and `limits.maxFrameChars` (4 MiB by default) bounds every frame on
  every transport, so a single file above roughly 3 MiB needs `limits` raised too.

## Publication, refresh and removal

`server.skills(list)` is atomic: every skill is validated, hashed and checked for conflicts, and
only then does the whole batch become visible. A failure publishes nothing (no half-exposed
files). Conflicts detected before commit:

- the same URI published with different bytes/mime/kind by two skills;
- a URI that is a file in one skill and a directory in another;
- a nested skill whose files differ from what its enclosing skill lists below that root
  (legitimate shared membership — identical content — is allowed; see below);
- a skill root or file inside a provider's namespace, or a provider namespace inside a skill;
- a URI already registered as a static resource, or matched by a registered resource template
  (`ResourceManager` refuses the reverse registrations too, instead of silently overwriting).

Re-publishing a skill root replaces it; `removeSkills(roots)` withdraws every URI of those skills.
Readers never observe a half-published generation: a concurrent `skills/list` sees either the old
or the new catalog (tested with concurrent republishing). A new publication legitimately
invalidates a client's held entry: its next `resources/read` fails verification (size/digest) and
it refreshes with `skills/get` — that is the spec's staleness protocol. There is no protocol-wide
version token and no promise that old bytes stay available.

Skill files are ordinary resources: `resources/list` lists the files of **listed** skills (never
directories, never unlisted skills), `resources/read` serves them, subscriptions/middleware/hooks
apply unchanged. `McpSkill.unlisted` keeps a skill out of `skills/list` and `resources/list` while
`skills/get`, `resources/read` and `resources/directory/read` still resolve it (a partial catalog).

### Nested skills

A skill may contain another skill's directory. From the parent's perspective the nested files are
supporting files (a nested `SKILL.md` is ordinary Markdown named `SKILL.md`). To publish the nested
skill as its own catalog entry, derive it — `parent.nestedSkill("daily-tieout")` — so both entries
describe the same bytes: the nested manifest is a subset of the parent's, the shared URIs carry the
same digests, and the directory index serves the nested root with its `SKILL.md` named by
frontmatter. Publishing a nested skill with a different file set is a conflict. Publishing a
nested entry never activates it: activation is the host's per-skill decision.

## Pagination

`skills/list` and `resources/directory/read` are paginated with opaque, stateless cursors bound to
the operation, the scope (the directory URI), the publication generation and the ordered item
identities of the listing. Ordering is deterministic (by URI; directory children by name); an entry
is indivisible — its `resources` array is never split across pages; the last page carries no
`nextCursor`. A cursor is rejected with `-32602` when malformed, issued by another operation or
directory, out of range, or stale (the catalog or directory changed, or a differently authorized
caller sees a different listing). Page sizes: `SkillSettings.listPageSize` (50) and
`directoryPageSize` (200). Nothing is retained server-side for cursors.

## Directory browsing

Every directory level of a published skill — the root and each subdirectory, explicit empty
directories included — is a directory resource (`mimeType: "inode/directory"`, canonical URI
without trailing slash). `resources/directory/read` returns the direct children only (files with
their ordinary `Resource` metadata including `size`, subdirectories as directory resources), an
empty array for an empty directory, and `-32602` for an unknown URI or a file. Directory resources
are addressable but not listed by `resources/list`.

Directory browsing is discovery, not authorization: a child a host did not have in its held entry
is a change to the skill, and the shared verifier reports a read of it as `UnlistedFile`. The host
refreshes the entry (`skills/get`) — which revokes any content-bound approval — before using it.

## Dynamic providers

```scala 3 raw
import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.wire.Resource as WireResource
import zio.*

object DailyReports extends SkillProvider[Any]:
  private val root = SkillUri.unsafeParse("skill://reports/daily")
  private val entry = Skill(
    uri = s"${root.render}/SKILL.md",
    frontmatter = SkillFrontmatter
      .fromFields(zio.Chunk("name" -> zio.json.ast.Json.Str("daily"), "description" -> zio.json.ast.Json.Str("Today's report.")))
      .toOption
      .get,
    resources = SkillResources.Dynamic // generated content: no stable digests
  )
  override def namespaces: List[SkillUri] = List(root)
  override def list(context: McpContext): ZIO[Any, Throwable, List[Skill]] = ZIO.succeed(List(entry))
  override def get(uri: String, context: McpContext): ZIO[Any, Throwable, Option[Skill]] =
    ZIO.succeed(Option.when(uri == entry.uri)(entry))
  override def read(uri: String, context: McpContext): ZIO[Any, Throwable, Option[SkillFileContent]] =
    ZIO.succeed(Option.when(uri == entry.uri)(SkillFileContent("---\nname: daily\ndescription: Today's report.\n---\n...", Some("text/markdown"))))
  override def readDirectory(uri: String, context: McpContext): ZIO[Any, Throwable, Option[List[WireResource]]] =
    ZIO.succeed(Option.when(uri == root.render)(List(WireResource(entry.uri, "daily", mimeType = Some("text/markdown")))))
```

Mount it with `server.skillProvider(DailyReports)` or `override def skillProviders = List(...)`.
Rules the registry enforces:

- a provider owns its `namespaces` (skill roots or wider prefixes); requests at or below them are
  routed to it, and namespaces may not overlap published skills or other providers;
- entries it returns are validated (`SkillVerifier.validateEntry`) and must lie within its
  namespaces — an invalid or out-of-namespace entry is a server-side `-32603`, never republished;
- `list` may return a subset or nothing; `get` must answer for everything the provider serves;
- a provider whose content is generated returns `SkillResources.Dynamic` and must not advertise
  digests it cannot honour on read; the `frontmatter` of an entry must equal the frontmatter of the
  `SKILL.md` that `read` returns (hosts compare them field by field);
- `list` / `get` / `readDirectory` are metadata operations and never read supporting files;
- `supportsDirectoryRead = false` switches the server's `directoryRead` flag off rather than
  letting a provider answer misleading empty listings;
- every method receives the request's `McpContext` (client capabilities, `_meta`, transport client
  key) for authorization scoping.

Server-side ingestion of a static snapshot (what `McpSkill` does) is separate from host-side lazy
retrieval (what the spec requires of hosts): the server hashes everything once at publication so it
can answer metadata requests without touching content afterwards.

## Authorization and caching

Skill requests go through the same router pipeline as everything else: `ServerHooks`, middleware,
the HTTP guards (`allowedHosts`/`allowedOrigins`, body and frame limits) and the request context
all apply. Authorization-scoped catalogs are expressed through a `SkillProvider` that consults the
`McpContext`; cursors issued for one caller's view are rejected for a caller who sees a different
listing.

Cache attributes default to the base protocol's conservative values, `ttlMs = 0` and
`cacheScope = "private"` (`SkillSettings.ttlMs` / `cacheScope`). Do not set `public` for a catalog
that varies by caller. The attributes are freshness hints, not integrity properties.

## JVM directory loading

```scala 3 raw
import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.skills.SkillDirectoryLoader
import java.nio.file.Paths

val loaded = SkillDirectoryLoader.load(
  Paths.get("./skills/refunds"),
  SkillDirectoryLoader.Options(namespace = List("acme", "billing")) // → skill://acme/billing/refunds
)
val server = McpServer("files")
val program = loaded.flatMap(server.skill) *> server.runStdio()
```

`SkillDirectoryLoader` (JVM only; a convenience adapter, not part of the shared core) loads a
caller-selected directory into an `McpSkill` that then goes through the same publication pipeline:

- **selection policy**: regular files only; dot-files/dot-directories and the default exclusions
  (`.git`, `.hg`, `.svn`, `node_modules`, `__pycache__`, `.DS_Store`, `Thumbs.db`) skipped
  (`Options.includeHidden`, `Options.exclude`); the manifest is computed from exactly the files
  that were read, so it never claims a tree different from the one served;
- **text vs binary**: files with a known text extension whose bytes are strict UTF-8 are served as
  `text`; everything else is a `blob` (a `.txt` that is not UTF-8 is a blob, never repaired);
- **safety**: the root is resolved once with `toRealPath`; every entry is inspected with
  `NOFOLLOW_LINKS`; symbolic links, devices and other non-regular entries fail the load
  (`Options.skipSpecialFiles = true` skips and reports them); files are opened with
  `NOFOLLOW_LINKS` (`O_NOFOLLOW` on POSIX), reads are bounded by `maxFileBytes`, and the entry's
  size and file key are re-read after the read so a replaced file is refused; every entry's real
  path must stay under the real root. This detects escape through a symlinked parent that existed
  at walk time and refuses obvious mid-read replacements; it is **not** race-proof against a hostile
  writer with concurrent access (the JDK has no portable `openat`-style traversal). The tree must
  belong to the operator; never load a path a remote MCP request supplied;
- **limits**: `maxFiles` (512), `maxTotalBytes` (16 MiB), `maxFileBytes`;
- the directory's name must equal the frontmatter `name` (Agent Skills rule) unless
  `Options.skillPath` names the full skill path explicitly;
- nothing is executed, no executable bit is honoured, no archive is extracted, nothing is installed.

## Verification helpers (host side)

This SDK has no MCP client layer, so the host-side half stops at pure, network-independent helpers
(`SkillVerifier`, `HeldEntry`, `SkillIdentity`) that a host or a test can use:

| Helper | Outcome |
|---|---|
| `SkillVerifier.validateEntry(entry)` | entry-level rules checkable without fetching: canonical `SKILL.md` URI, final segment = `frontmatter.name`, non-empty duplicate-free manifest containing the skill's own URI, every URI within the root, digest format, non-negative sizes, checked total, the 512 / 16 MiB limits (optional). |
| `SkillVerifier.verifyFile(held, uri, bytes)` | `Verified(uri, size, digest)` when size and digest match the held entry; `Failed(UnlistedFile)` for a file the entry does not list; `Failed(SizeMismatch / DigestMismatch)` otherwise; `Unverifiable(uri)` for a dynamic entry (after a containment check). |
| `SkillVerifier.verifySkillMd(held, bytes)` | `verifyFile` plus a field-by-field comparison of the parsed frontmatter with the entry's — a discrepancy is `Failed(FrontmatterMismatch)` even for a dynamic entry. |
| `SkillVerifier.verifyContents(held, resourceContents)` | decodes a `resources/read` payload (`text` → UTF-8 bytes, `blob` → base64-decoded bytes) and verifies it. |
| `HeldEntry(serverLabel, entry)` | an immutable held entry keyed by `SkillIdentity(hostAssignedServerLabel, uri)`; `approvalKey` is the `(uri, digest)` set a persisted approval binds to (`None` for dynamic); `sameContentSet(fresh)` tells whether a refreshed entry revokes it. |

A dynamic skill can be valid without being integrity-verified (`Unverifiable`). Verification
never returns success for an unlisted file, a stale digest, a size mismatch or a metadata
mismatch, and never refreshes a held entry on failure — refreshing (and re-approving) is the
host's explicit step.

## Host responsibilities (not enforced by the server)

The server serves bytes and digests. Everything below is the host's, and nothing in this SDK
claims otherwise:

- **Digests prove consistency, not authorship.** They are unsigned and come from the same server as
  the content; an intermediary can rewrite both. A digest match is not a security boundary.
- **Reading is not activating.** A `resources/read` of a `SKILL.md` grants no approval and opens no
  acting window; only the host's skill-loading path — verify, approve, then load — activates.
- **Skill content is untrusted model input**, a higher-risk surface than a tool call. Tag it with
  the originating server's host-assigned label; never present it as a local skill.
- **No implicit execution or permission grants.** `allowed-tools` and any other permission-widening
  frontmatter are metadata to be ignored or approval-gated for MCP-origin skills; running bundled
  scripts or host tools while acting on a skill needs explicit per-skill approval.
- **Nested skills need fresh consent.** Approving a parent approves the parent alone; a nested
  `SKILL.md` read as a supporting file is Markdown, and its frontmatter has no effect.
- **Identity is (host label, uri).** Never key registry, approvals or cache on the URI alone or on
  the server's self-reported name; a same-named skill from another origin must never shadow.
- **Content-bound approval.** Bind a persisted approval to the entry's `(uri, digest)` set; a fresh
  entry with a different set revokes it; a dynamic skill cannot be content-bound.
- **Lazy retrieval and cache isolation.** Fetch `SKILL.md` at load and supporting files at read, keep
  any disk cache outside filesystem-skill discovery paths and either host-private or re-hashed on
  every access, and never install MCP-served skills into local auto-discovery directories.

## Platform support

| | JVM | Scala.js / Bun | Scala Native |
|---|---|---|---|
| Wire types, URI policy, frontmatter, snapshots, registry, router | ✅ | ✅ | ✅ |
| `skills/list`, `skills/get`, `resources/directory/read` over stdio | ✅ | ✅ | ✅ |
| … over HTTP (2026-07-28 stateless + legacy session adapter) | ✅ | ✅ | not available (no HTTP backend, by design) |
| SHA-256 | `MessageDigest` | portable FIPS 180-4 | portable FIPS 180-4 |
| `SkillDirectoryLoader` | ✅ | — (in-memory / provider only) | — (in-memory / provider only) |
| Verification helpers | ✅ | ✅ | ✅ |

Directory loading is a convenience adapter, not a prerequisite: Scala.js and Native servers publish
in-memory skills and providers with the same API.

## Interoperability status

See [skills-conformance.md](./skills-conformance.md) for the pinned specification revisions, the
requirement-to-test matrix, the upstream conformance scenarios that were run, and the known
discrepancies. No claim is made about any particular host application: only the peers that were
actually exercised (the official TypeScript SDK client over stdio and the upstream conformance
harness over HTTP) are listed there.
