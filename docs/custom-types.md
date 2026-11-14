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
- "Collections" means `List`, `Vector`, `Set`, `Seq`, `Array`, `Map[String, V]` and `Either` (as
  zio-json's `{"Left": ...}` / `{"Right": ...}` objects), each with derived element types; a
  parameter type the annotation path cannot derive is a compile error naming the parameter and the
  `given JsonDecoder[T]` / `McpInputCodec[T]` remedy.
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
- Java enums (`java.util.concurrent.TimeUnit`, your own) derive like Scala 3 enums: a string-enum
  schema of the constant names and a codec that reads and writes exactly those names.
  `java.time.DayOfWeek` and `java.time.Month` advertise their names too (zio-json's decoders for
  the two also accept other letter cases, but input validation requires the listed spelling).
- Sealed-trait hierarchies as a field type are not derived: provide a `given McpInputCodec[T]` for
  them.
- A tuple on the input side is the object `{"_1": ..., "_2": ...}` its schema describes, wherever it
  appears: as a parameter, inside `Option`, a collection or a map, and as a field of a case class
  whose decoder is derived by the library. A `JsonDecoder` you define yourself for the tuple, or
  for the case class around it (`derives JsonDecoder` included), keeps zio-json's JSON-array shape
  and then contradicts the derived schema; give that type an `McpSchema` / `McpInputCodec`. On the
  output side a tuple is always written as a JSON array, while a derived `outputSchema` describes
  the object. Prefer case classes to tuples in results.
- A typed tool's `In` must be a case class or `Unit` (`McpTool[Unit, String]` for a tool without
  arguments; `Map[String, V]` and types with a user `McpSchema` also qualify) and
  `.withOutputSchema` needs a case-class (or `Unit`) `Out`: MCP `arguments` and `structuredContent`
  are always JSON objects, so a scalar, `Option`, collection, `Either` or enum root is a
  compile-time error naming the type and the fix.
- Typed results without `.withOutputSchema`: a bare Scala 3 enum `Out` (`McpTool[In, Color]`) is
  written as its case name, and `List[T]` / `Vector[T]` / `Seq[T]` / `Set[T]` of a type with a
  `JsonEncoder` as a JSON array. A list whose elements are themselves collections
  (`List[List[Int]]`) is ambiguous: wrap it in a case class. Write `Option.empty[T]` rather than
  `None` where the builder overloads would otherwise be ambiguous. When no encoder exists the
  compiler says `No McpEncoder[T] found` and lists the remedies.
- Derived schemas use the `format` keywords `date-time`, `date`, `time`, `duration`, `uuid` and
  `uri` for the matching `java.time` / `UUID` / `URI` types; validators running in strict mode
  (`ajv --strict`) need their formats plugin (`ajv-formats`) to accept them.

## What the server enforces

The `inputSchema` a tool advertises on `tools/list` is also **enforced on `tools/call`**: before a
handler (or an annotated method) runs, the arguments are checked against the schema, and a call
that does not conform is answered with an `isError` tool result naming the location and the
constraint — the same in-band answer an undecodable argument gets. The handler is not invoked. This
holds for derived schemas, `@Param(schema = ...)` fragments, `McpSchema` / `McpInputCodec` schemas
and `McpTool.withSchema`, on every transport, on the JVM, Scala.js and Scala Native.

A missing required argument is such a case. Both registration paths answer it with the same
`isError` result, `Invalid arguments for tool '<tool>': missing required property '<name>'`, and
not with a JSON-RPC `-32602`: the MCP specification (2026-07-28, *Tools* § *Error Handling*) keeps
protocol errors for unknown tools and malformed requests, and lists input validation errors under
tool execution errors, which are "reported in tool results with `isError: true`" so that the model
can correct the call. With enforcement off the decoder reports it instead, again with one message
on both paths: `Missing required argument '<name>'`.

The check is a built-in, dependency-free validator
(`com.tjclp.fastmcp.core.JsonSchemaValidator`) for a subset of JSON Schema:

| Enforced | Keywords |
|---|---|
| Any value | `type` (a name or a list), `enum`, `const` |
| Numbers | `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum` |
| Strings | `minLength`, `maxLength` (Unicode code points) |
| Arrays | `items`, `prefixItems` (and array-form `items` with `additionalItems`), `minItems`, `maxItems` |
| Objects | `properties`, `required`, `additionalProperties` |
| Combinators | `allOf`, `anyOf`, `oneOf`, `not` |
| References | `$ref` to a location in the same schema (`#/$defs/...`, `#/definitions/...`, any JSON Pointer) |

**Not enforced — advertised only:**

- `pattern` and `patternProperties`. Matching a client-supplied string with a backtracking regular
  expression engine is not linear-time, so the server does not evaluate patterns. Check the string
  in the handler, or in an `McpInputCodec` decoder as in the example below.
- `format`, `multipleOf`, `uniqueItems`, `minProperties`, `maxProperties`, `propertyNames`,
  `contains` / `minContains` / `maxContains`, `dependentRequired` / `dependentSchemas` /
  `dependencies`, `if` / `then` / `else`, `unevaluatedProperties` / `unevaluatedItems`, the
  `content*` keywords, `$dynamicRef` / `$recursiveRef`.
- A `$ref` that is not local, does not resolve, or is recursive (it leads back to a schema that is
  still being resolved): that reference accepts any value.
- `additionalProperties` when `patternProperties` is also present.
- A supported keyword whose value has the wrong shape (`"maximum": "10"`).

Everything else (`description`, `title`, `default`, `examples`, vendor keywords) is an annotation.
A handler that relies on an unenforced constraint must check it itself. The derived schemas use two
unenforced keywords, and in both cases the decoder does the work: `format` on `java.time` / `UUID` /
`URI` types (the value is parsed) and `uniqueItems` on `Set` (duplicates collapse).

Ask a server what it enforces:

```scala 3 raw
server.toolInputValidation
// Map(search -> ToolInputValidation(enforced = true, unenforced = List()),
//     lookup -> ToolInputValidation(enforced = true, unenforced = List(pattern)))
```

Turning it off:

```scala 3 raw
import com.tjclp.fastmcp.server.manager.ToolRegistrationOptions

// one tool: advertise the schema, do not enforce it
server.tool(myTool, ToolRegistrationOptions(validateInputSchema = false))

// the whole server (also the switch for McpServerApp: override `settings`)
McpServer("name", settings = McpServerSettings(validateToolInput = false))
```

With enforcement off a schema is documentation, and every handler must check what it relies on.

Because the advertised schema is now the contract, it has to describe what the tool really accepts:

- A call that omits a `required` member is rejected. A parameter or input field is `required`
  unless it is an `Option` or has a Scala default: a client may omit those, and the handler gets
  `None` or the default. `@Param(..., required = true)` puts an `Option` or defaulted member back
  in `required`. This applies to `@Tool` parameters, to the fields of a typed tool's `In`, and to
  case classes nested inside either.
- The default is applied by the derived decoder. If a type has a hand-written `JsonDecoder` that
  does not fill in the default, mark the field `@Param(..., required = true)` or give the type a
  `McpSchema` / `McpInputCodec`, so that the schema does not promise what the decoder refuses.
- An `outputSchema` (`.withOutputSchema`) is different: a field of `Out` with a default stays in
  `required`, because the encoder always writes it.
- A hand-written schema that is stricter than, or simply different from, what the decoder reads
  (a custom `JsonDecoder` for an enum with its own spelling, say) now rejects what the schema does
  not describe. Supply a matching `McpSchema` / `McpInputCodec`.
- `oneOf` means exactly one: alternatives that do not exclude each other (object alternatives
  without `required`, for instance) reject every value that matches two of them.
- A tool with no parameters derives `"additionalProperties": false`, so a call that sends
  arguments to it is rejected.
- A `@Param(schema = ...)` fragment on an `Option` parameter replaces the derived
  `anyOf [..., {"type":"null"}]` wrapper. An omitted argument is still fine; an explicit `null`
  conforms only if the fragment allows it (`"type": ["string", "null"]`).

Cost: a schema is compiled once at registration, and a call is one walk over its arguments with no
regular expressions, no number expansion and no table keyed by client-chosen names.

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

The `pattern` above tells clients what a valid id looks like, but the server does not evaluate
patterns ([What the server enforces](#what-the-server-enforces)): the decode function is what
rejects a bad id, so make it check everything the pattern promises. The call then fails as a tool
error (`isError`) that names the argument, on both registration paths:
`Failed to decode parameter 'id' from JSON: (Invalid user id 'nope'). Value: "nope"` (until 1.1.0
a typed contract named the tool there instead of the argument).

## Per-field override: `@Param(schema = ...)`

For a one-off field, `@Param(schema = Some("..."))` replaces that field's generated schema with a
raw JSON Schema fragment. The `description` and `examples` of the same `@Param` are added to the
fragment, unless the fragment states its own `description` / `examples`, which then win. This is
the right tool for enum constraints, length limits or numeric bounds that Scala types cannot
express, and the fragment is enforced on `tools/call` (a `pattern` or `format` in it is advertised
only — check those in the method). The literal is parsed when the server is compiled: one that is
not JSON, or not a JSON object (or `true` / `false`), is a compile-time error.

The parameter below is advertised as
`{"type":"string","enum":["relevance","date"],"description":"Sort order"}`:

```scala 3 raw
@Param(
  description = "Sort order",
  schema = Some("""{"type": "string", "enum": ["relevance", "date"]}""")
)
sortBy: String
```

## Whole-tool override: `McpTool.withSchema`

An entire typed tool can opt out of derivation and supply its `inputSchema` explicitly. The schema
is enforced like a derived one, so it must describe what the `In` decoder accepts. The Bun HTTP
example does exactly this:
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
