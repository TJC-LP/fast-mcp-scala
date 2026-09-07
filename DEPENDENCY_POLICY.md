# Dependency Policy

fast-mcp-scala is a library consumed by downstream builds, so it takes a conservative approach to
dependency updates: versions stay stable unless there is a concrete reason to move them.

## Where versions live

All production dependency versions are pinned in one place, the `Versions` object in
[`build.mill`](build.mill). The test-only TypeScript MCP SDK used as a conformance client is pinned
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

- a **security vulnerability** is disclosed in it (GitHub security alerts are enabled on the repo);
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
- Protocol-version support (which MCP revisions the server negotiates) is documented in
  [docs/spec-coverage.md](docs/spec-coverage.md) and changes only in minor or major releases.

## Reporting a dependency problem

Open an issue labeled `bug` (or, for a vulnerability, follow [SECURITY.md](SECURITY.md)). Include
the dependency, the version, and the effect on fast-mcp-scala.
