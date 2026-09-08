# Dependency Policy

fast-mcp-scala is a library consumed by downstream builds, so it takes a conservative approach to
dependency updates: versions stay stable unless there is a concrete reason to move them.

## Where versions live

All production dependency versions are pinned in [`build.mill`](build.mill): the `Versions`
object (ZIO, zio-json, zio-http, Scala.js, Scala Native and the test libraries), `scala3Version`,
and the `//| mvnDeps` header that pins the mill-bun plugin. The test-only TypeScript MCP SDK used as a conformance client is pinned
in the js module's `bunDevDeps` and frozen by the committed
[`fast-mcp-scala/js/bun.lock`](fast-mcp-scala/js/bun.lock); it never appears in the published
artifacts.

The CI-only inputs are pinned the same way. The MCP conformance harness is pinned in
[`conformance/package.json`](conformance/package.json) and frozen by the integrity-hashed
[`conformance/bun.lock`](conformance/bun.lock); `scripts/conformance.sh` installs it with
`bun install --frozen-lockfile` and runs the installed binary, so a resolution that differs from
the lock fails before the suite starts (to bump, edit `package.json`, reinstall with the
Mill-managed Bun, and review the lock diff). Every GitHub Action in the workflows and the
`setup-build` composite is pinned to a full commit SHA, and
[`.github/dependabot.yml`](.github/dependabot.yml) refreshes those pins and the harness lock weekly
with a 7-day cooldown. The Mill launcher distribution named by `.mill-version` is verified against
[`.github/mill-dist.sha256`](.github/mill-dist.sha256) in every CI job, so a Mill bump must add the
new distributions' SHA-256 lines in the same PR.

## When dependencies change

A production dependency is updated when one of these applies:

- a **security vulnerability** is disclosed in it. Detection is an explicit OSV audit of the
  resolved Maven tree (`cs resolve` of the published coordinates queried against
  `api.osv.dev/v1/querybatch` — `scripts/osv-scan.sh`, run weekly and on every build-file change by
  `.github/workflows/osv.yml`, before every release, and recorded per release in the gate ledger):
  GitHub's dependency graph does not see Mill-resolved Maven dependencies, so its security alerts,
  when enabled, cover only the Actions and bun ecosystems declared in `.github/dependabot.yml`. A
  transitive dependency with a security surface of its own (netty, which reaches the JVM artifact
  only through zio-http) is not declared directly: its version is governed by the
  `io.netty:netty-bom` import the JVM module publishes in its POM (`Versions.netty`, kept equal to
  the version zio-http declares — 4.2.17.Final with zio-http 3.11.4), so it can still move ahead of
  the library that brings it when an advisory lands first, without becoming a direct dependency
  that a stdio-only consumer would have to exclude on its own;
- a bug in the dependency affects fast-mcp-scala's behavior;
- a new dependency feature is needed;
- the dependency drops support for a Scala, Scala.js, Scala Native, or JDK version this library
  still targets;
- a **platform LTS move**: the library tracks the Scala 3 LTS line (currently 3.9) and the LTS JDKs
  (17, 21, 25 in CI). Moving to a new LTS is a deliberate, changelog-noted change, never an
  incidental bump.

Routine version bumps without a motivation are avoided so downstream projects with strict
dependency policies are not forced to move transitively.

## What we do not do

- No scheduled bumps of production dependencies (ZIO, zio-json, zio-http, mill-bun-plugin).
- No unpinned dependency ranges anywhere in the build.

## Compatibility promises

- Within a major version, public API and binary compatibility are preserved for the JVM artifact
  (a MiMa gate against `1.0.0` is planned; see [ROADMAP.md](ROADMAP.md)). Breaking changes are
  reserved for major versions and announced in the [CHANGELOG](CHANGELOG.md).
- Removing a deprecated member happens at the next major version after the deprecation, unless the
  member never shipped in a stable release.
- The promise covers the public API of `fast-mcp-scala_3` except `com.tjclp.fastmcp.examples.*`
  (the example servers) and `com.tjclp.fastmcp.macros.*` (the annotation macros' own machinery),
  which may change in minor releases. The `_sjs1_3` and `_native0.5_3` artifacts follow the same
  source-compatibility rules; binary compatibility is checked for the JVM artifact only, with
  `1.0.0` as the MiMa baseline (the release candidates are not baselines).
- Protocol-version support (which MCP revisions the server negotiates) is documented in
  [docs/spec-coverage.md](docs/spec-coverage.md) and changes only in minor or major releases.

## Reporting a dependency problem

Open an issue labeled `bug` (or, for a vulnerability, follow [SECURITY.md](SECURITY.md)). Include
the dependency, the version, and the effect on fast-mcp-scala.
