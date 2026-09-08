# Customizing input and output types

← [README](../README.md) · see also [Architecture § The annotation path at compile time](./architecture.md#the-annotation-path-at-compile-time)

fast-mcp-scala derives both decoding and JSON Schema natively on JVM, Scala.js, and Scala Native.
There is no schema-library import at call sites.

## What derives automatically

Primitives, `java.time` values, Scala 3 enums, case classes (nested included), `Option`,
collections, and string-keyed maps work without per-type givens, on the annotation path *and* in
typed contracts.

- An enum field derives a string-enum JSON Schema (`{"type":"string","enum":[...]}`) and a
  string-based codec, at any nesting depth, including through `Option`, collections, and nested
  case classes.
- A hand-written `given JsonDecoder` / `JsonEncoder` for a type always wins over the derived one,
  custom naming and all. Derivation is macro-side and summon-first; the library never exports
  givens that could shadow yours.
- Result (`Out`) case classes need no hand-written `JsonEncoder`: `McpEncoder` falls back to
  `JsonEncoder[A]`, and case classes without any `JsonEncoder` in scope derive one automatically
  (Mirror-based, `NotGiven`-guarded).
- Enums with parameterized cases keep zio-json's wrapper-object encoding. Provide an
  `McpInputCodec` for a custom shape.
- Union types (`A | B`), literal types (`"asc" | "desc"`) and opaque types are **not** derived: the
  macro aborts with `Cannot derive an MCP JSON Schema for ...`. Supply a `given McpInputCodec[T]`
  (below) for such a field.
- Sealed-trait hierarchies as a field type and Java enums (`java.util.concurrent.TimeUnit`,
  `java.time.DayOfWeek`) are not derived either: provide a `given McpInputCodec[T]` for them.
  Prefer case classes to tuples — a tuple field advertises an object schema (`_1`, `_2`) while the
  decoder reads a JSON array.
- Typed results: an enum *field* of `Out` derives, but a bare enum `Out` (`McpTool[In, Color]`)
  needs a `given JsonEncoder[Color]`; for collection results use `Seq[T]` / `Set[T]` or a case
  class rather than `List[T]` / `Vector[T]` (the encoder summon is ambiguous for those); write
  `Option.empty[T]` rather than `None` where the builder overloads would otherwise be ambiguous.
  A typed tool with no arguments takes `case class NoArgs()` (there is no `McpDecoder[Unit]`).
- Derived schemas use the `format` keywords `date-time`, `date`, `time`, `duration`, `uuid` and
  `uri` for the matching `java.time` / `UUID` / `URI` types; validators running in strict mode
  (`ajv --strict`) need their formats plugin (`ajv-formats`) to accept them.

## `McpInputCodec[T]`: one value, decoder plus schema

For a domain type whose wire representation differs from its Scala shape, define one
`McpInputCodec[T]`. It is simultaneously the zio-json decoder used inside request case classes and
the schema advertised to MCP clients:

```scala 3 raw
opaque type UserId = String

object UserId:
  extension (id: UserId) def value: String = id

  given McpInputCodec[UserId] = McpInputCodec.string(
    """{"type":"string","pattern":"^usr_[a-z0-9]+$"}"""
  ) { raw =>
    Either.cond(raw.startsWith("usr_"), raw, s"Invalid user id '$raw'")
  }

case class LookupArgs(id: UserId)
```

## Per-field override: `@Param(schema = ...)`

For a one-off field, `@Param(schema = Some("..."))` replaces that field's generated schema with a
raw JSON Schema fragment. This is the right tool for enum constraints, patterns, or numeric bounds
that Scala types cannot express:

```scala 3 raw
@Param(
  description = "Sort order",
  schema = Some("""{"type": "string", "enum": ["relevance", "date"]}""")
)
sortBy: String
```

## Whole-tool override: `McpTool.withSchema`

An entire typed tool can opt out of derivation and supply its `inputSchema` explicitly. The Bun
HTTP example does exactly this:
[`HttpServerJs.scala`](../fast-mcp-scala/js/src/com/tjclp/fastmcp/examples/HttpServerJs.scala).

```scala 3 raw
private val greetSchema = ToolInputSchema.unsafeFromJsonString(
  """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
)

private val greetTool = McpTool.withSchema[GreetArgs, GreetResult](
  name = "greet",
  inputSchema = greetSchema,
  description = Some("Say hello")
)(args => GreetResult(s"Hello, ${args.name}!"))
```

## Returning embedded resources

`EmbeddedResource` (re-exported by `com.tjclp.fastmcp`) wraps a `ResourceContents` payload whose
concrete types `TextResourceContents` / `BlobResourceContents` live in `com.tjclp.fastmcp.core.wire`
and are root-exported since 1.0.0 (the named import below is what the release candidates needed;
it stays valid). They replaced `EmbeddedResourceContent` in 0.5.0 — `mimeType` is `Option[String]`,
`text` / `blob` are plain `String`:

```scala 3 raw
import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.wire.{BlobResourceContents, TextResourceContents}

val memo: Content =
  EmbeddedResource(TextResourceContents("memo://today", "Ship it.", mimeType = Some("text/plain")))

val logo: Content =
  EmbeddedResource(BlobResourceContents("img://logo", base64Png, mimeType = Some("image/png")))
```

## `McpSchema[T]` for output-only nested types

For a nested type that only appears in results, `McpSchema[T]` provides the schema without
requiring a decoder.

## `McpDecoder[T]` for low-level input conversion

Implement `McpDecoder[T]` directly only for a low-level input conversion that does not need
automatic nested case-class derivation. Any `given JsonDecoder[T]` is lifted to an `McpDecoder[T]`
automatically on every platform.

## Migrating from Tapir `Schema` overrides

Tapir, ApiSpec, Circe, and Cats are no longer production dependencies since 1.0.0 (the RC1–RC3
prereleases still shipped them and required `sttp.tapir.generic.auto.*` for typed contracts).
Existing `sttp.tapir.Schema` overrides map onto the tools above:

| Before | After |
|---|---|
| `given Schema[T]` for a wire-shape change | `given McpInputCodec[T]` |
| `given Schema[T]` for an output-only type | `given McpSchema[T]` |
| `given Schema[T]` for a union- or literal-typed field | `given McpInputCodec[T]` |
| Per-field `.description` / constraint tweaks | `@Param(description = ..., schema = ...)` |
| Whole-tool hand-written schema | `McpTool.withSchema` |
