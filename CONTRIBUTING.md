# Contributing to fast-mcp-scala

Thanks for helping build a Scala 3 MCP server library. This guide covers the local build, the
quality gates CI enforces, and how changes get in. For a tour of the code itself see
[docs/architecture.md](docs/architecture.md); [CLAUDE.md](CLAUDE.md) is the condensed quick
reference kept in sync with this file.

## Prerequisites

- **JDK 17 or newer.** CI tests the LTS releases 17, 21, and 25.
- **Mill** comes with the repo: the `./mill` wrapper reads `.mill-version` (1.1.8). Nothing to install.
- **Bun.** The build provisions its own pinned Bun (1.4.1) through the mill-bun plugin, so the
  Scala.js tests need nothing extra. A system `bun` is required only for
  `scripts/conformance.sh`, which fetches the conformance harness with `bunx`.
- **clang/LLVM ≥ 17** for the Scala Native module (`brew install llvm` on macOS; preinstalled on
  the Ubuntu CI runners).
- **jq** for `scripts/native-smoke.sh`.
- **scala-cli** (optional) for the runnable snippets in `scripts/*.sc` and the docs.
- GraalVM is **not** required: Mill provisions it for the `nativeSmoke` modules through the
  coursier JVM index.

## Getting started

```bash
git clone https://github.com/TJC-LP/fast-mcp-scala.git   # or your fork
cd fast-mcp-scala
./mill fast-mcp-scala.compile     # JVM + Scala.js + Scala Native
./mill fast-mcp-scala.test        # all three platforms
```

## Build, test, format

```bash
# Aggregates (JVM + Scala.js + Scala Native)
./mill fast-mcp-scala.compile
./mill fast-mcp-scala.test
./mill fast-mcp-scala.reformat                      # auto-format every Scala source
./mill fast-mcp-scala.checkFormat                   # what CI runs

# Single platform
./mill fast-mcp-scala.jvm.test
./mill fast-mcp-scala.js.test                       # Scala.js conformance tests on Bun
./mill fast-mcp-scala.scalaNative.test              # links a native test binary
./mill fast-mcp-scala.jvm.test com.tjclp.fastmcp.macros.ToolProcessorTest

# Scala.js / Bun housekeeping
./mill fast-mcp-scala.js.bunLock                    # regenerate js/bun.lock after changing bunDevDeps

# Binaries
./mill fast-mcp-scala.scalaNative.nativeLink        # standalone LLVM binary of AnnotatedServer
./mill fast-mcp-scala.nativeSmoke.stdio.nativeImage # GraalVM image of AnnotatedServer
```

### Project layout

Sources are split into `fast-mcp-scala/shared/` plus one tree per platform (`jvm/`, `js/`,
`native/`). Every module compiles exactly `shared/src/` + its own platform tree, and nothing
reaches across platform trees; that invariant is what lets `shared/` build on all three targets.
Module definitions live in `fast-mcp-scala/package.mill`; versions, compiler flags, and shared
traits live in `build.mill`. The full map is in
[docs/architecture.md § Module layout](docs/architecture.md#module-layout).

### Working on macros

The annotation and schema-derivation macros live in `fast-mcp-scala/shared/src/com/tjclp/fastmcp/macros/`
and compile on all three platforms. Incremental builds go stale after macro edits or file moves,
and expansion then fails with a `NoClassDefFoundError` or a spurious `-Xcheck-macros`
"Malformed tree". Rebuild clean:

```bash
rm -rf out/fast-mcp-scala && ./mill fast-mcp-scala.compile
```

## Quality gates

Every pull request must pass:

- **Scalafmt** — `./mill fast-mcp-scala.checkFormat` (config in `.scalafmt.conf`; run `reformat`
  before committing).
- **WartRemover** (configured in `build.mill`) — build errors on `Null`, `TryPartial`,
  `TripleQuestionMark`, `ArrayEquals`; warnings on `Var`, `Return`, `AsInstanceOf`, `IsInstanceOf`.
- **Whitespace** — `git diff --check`.
- **Tests** on all three platforms (`ci.yml`).
- **Official MCP conformance** (`conformance.yml`) against the JVM and Bun servers, and the same
  server as a GraalVM native image (`native.yml`). Run locally with
  `scripts/conformance.sh {jvm|js|native} [port] [active|2026]`. The expected-failure baselines in
  `conformance/` are **empty and stay empty**: fix the regression, never grow a baseline.
- **Native stdio smoke** (`native.yml`) — `scripts/native-smoke.sh [binary]` drives a stdio binary
  through the full MCP handshake.

## Consuming a local build

```bash
./mill -i __.publishLocal
```

publishes all three artifacts (`fast-mcp-scala_3`, `fast-mcp-scala_sjs1_3`,
`fast-mcp-scala_native0.5_3`) to `~/.ivy2/local` at the version printed by

```bash
./mill show fast-mcp-scala.jvm.publishVersion
```

which is the `publishVersion` default in `build.mill` (a `-SNAPSHOT` during development). Use that
version in your project:

```scala 3 ignore
// sbt
libraryDependencies += "com.tjclp" %% "fast-mcp-scala" % "<version>"   // %%% for Scala.js / Native

// Mill
def mvnDeps = Seq(mvn"com.tjclp::fast-mcp-scala:<version>")

// scala-cli
//> using dep com.tjclp::fast-mcp-scala:<version>
```

Or point `scala-cli` at a built JAR directly:

```scala 3 ignore
//> using scala 3.9.0
//> using jar "/absolute/path/to/out/fast-mcp-scala/jvm/jar.dest/out.jar"
//> using options "-Xcheck-macros" "-experimental"
```

## Making changes

1. Branch from `main`. Open an issue first for anything non-trivial so scope can be agreed.
2. Platform-independent code goes in `shared/src/`; platform code stays in its own tree.
3. Add tests in `jvm/test/src/`, `js/test/src/`, or `native/test/src/`.
4. User-visible changes get an entry under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md)
   (Keep a Changelog format).
5. Run `./mill fast-mcp-scala.reformat` and `./mill fast-mcp-scala.test`.
6. Open a pull request. PRs are **squash-merged**, so keep each PR self-contained rather than
   stacking one on another. Prefer follow-up commits over force-pushes during review.

## Reporting issues

Use the issue templates. Triage applies the labels the
[MCP SDK tiering system](https://modelcontextprotocol.io/community/sdk-tiers) standardizes:
type (`bug`, `enhancement`, `question`), status (`needs confirmation`, `needs repro`,
`ready for work`, `good first issue`, `help wanted`), and priority (`P0`–`P3`). Security issues go
through [SECURITY.md](SECURITY.md), never a public issue.

## Releasing (maintainers)

- `build.mill` holds a `-SNAPSHOT` default during development.
- A release-prep PR strips the suffix, dates the CHANGELOG section, and updates version pins in
  the README and docs.
- An annotated tag `vX.Y.Z` on the merge commit triggers `.github/workflows/release.yml`, which
  runs the full test suite and publishes all three artifacts to Maven Central. A `-` qualifier in
  the tag (`v1.0.0-RC1`) marks the GitHub release as a prerelease.
- A follow-up PR bumps the default back to the next `-SNAPSHOT`.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
