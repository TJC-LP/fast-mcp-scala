package com.tjclp.fastmcp.core

import scala.annotation.StaticAnnotation

/** Marker annotation for methods representing MCP Tools
  *
  * This annotation is processed at compile time by the FastMCPScala.scanAnnotations macro, which
  * automatically registers the method as an MCP tool.
  *
  * The tool's description will be taken from the `description` parameter if provided. If
  * `description` is `None` or omitted, the macro uses the first paragraph of the method's Scaladoc
  * comment, as one line of plain text: the comment markers are removed and the text stops at the
  * first blank line, `@tag` or code block. The same holds for `@Prompt` and `@Resource`.
  *
  * The `Option` arguments the macro reads (`name`, `description`, `title`, `taskSupport` and the
  * boolean hints) must be literals — `Some("...")`, `Option("...")`, `None`, or a `final val`
  * constant; a non-literal argument is a compile-time error rather than being silently dropped.
  *
  * `examples`, `version`, `deprecated`, `deprecationMessage`, `tags` and `timeoutMillis` are
  * metadata only: `scanAnnotations` never reads them and no wire shape carries them. They are
  * deprecated since 1.0.0 (as are the matching [[ToolDefinition]] fields) and are removed in 2.0.0.
  * `@Param(examples = ...)` is unrelated and stays: it feeds the schema's `examples` array.
  *
  * @param name
  *   Optional name for the tool. Must be unique per scanned object (a duplicate is a compile-time
  *   error), so overloaded annotated methods need explicit names. When omitted, the method name is
  *   used (a description-only annotation still registers under the method name).
  * @param description
  *   Optional description for the tool. If None, the first paragraph of the Scaladoc is used.
  * @param examples
  *   Deprecated since 1.0.0 — metadata only, never read or emitted; removed in 2.0.0.
  * @param version
  *   Deprecated since 1.0.0 — metadata only, never read or emitted; removed in 2.0.0.
  * @param deprecated
  *   Deprecated since 1.0.0 — metadata only, never read or emitted; removed in 2.0.0.
  * @param deprecationMessage
  *   Deprecated since 1.0.0 — metadata only, never read or emitted; removed in 2.0.0.
  * @param tags
  *   Deprecated since 1.0.0 — metadata only, never read or emitted; removed in 2.0.0.
  * @param timeoutMillis
  *   Deprecated since 1.0.0 — metadata only, never read or emitted; removed in 2.0.0.
  * @param title
  *   Optional human-readable title for the tool
  * @param readOnlyHint
  *   If Some(true), the tool only reads data and does not modify anything
  * @param destructiveHint
  *   If Some(true), the tool may perform destructive/irreversible operations
  * @param idempotentHint
  *   If Some(true), calling multiple times with the same args has the same effect
  * @param openWorldHint
  *   If Some(true), the tool interacts with the external world (network, filesystem, etc.)
  * @param returnDirect
  *   If Some(true), the result should go directly to the user without LLM post-processing
  * @param taskSupport
  *   Per-tool opt-in for experimental MCP Tasks (spec 2025-11-25). One of `"forbidden"` (default,
  *   no tasks), `"optional"` (clients may augment with a task), or `"required"` (clients must). Has
  *   no effect unless [[com.tjclp.fastmcp.server.TaskSettings.enabled]] is true on the server.
  */
class Tool(
    val name: Option[String] = None,
    val description: Option[String] = None,
    @deprecated("metadata only; not emitted on the wire; removed in 2.0.0", "1.0.0")
    val examples: List[String] = List.empty,
    @deprecated("metadata only; not emitted on the wire; removed in 2.0.0", "1.0.0")
    val version: Option[String] = None,
    @deprecated("metadata only; not emitted on the wire; removed in 2.0.0", "1.0.0")
    val deprecated: Boolean = false,
    @deprecated("metadata only; not emitted on the wire; removed in 2.0.0", "1.0.0")
    val deprecationMessage: Option[String] = None,
    @deprecated("metadata only; not emitted on the wire; removed in 2.0.0", "1.0.0")
    val tags: List[String] = List.empty,
    @deprecated("metadata only; not emitted on the wire; removed in 2.0.0", "1.0.0")
    val timeoutMillis: Option[Long] = None,
    // MCP Tool Annotations (behavioral hints for clients)
    val title: Option[String] = None,
    val readOnlyHint: Option[Boolean] = None,
    val destructiveHint: Option[Boolean] = None,
    val idempotentHint: Option[Boolean] = None,
    val openWorldHint: Option[Boolean] = None,
    val returnDirect: Option[Boolean] = None,
    // Experimental MCP Tasks (spec 2025-11-25)
    val taskSupport: Option[String] = None
) extends StaticAnnotation

/** Unified annotation for method parameters across Tools, Resources, and Prompts
  *
  * This annotation can be used with any @Tool, @Resource, or @Prompt annotated method to provide
  * descriptions and metadata for parameters.
  *
  * @param description
  *   Description of the parameter for documentation
  * @param examples
  *   List of example values for the parameter (follows JSON Schema specification)
  * @param required
  *   Whether the parameter is required. When not written, an `Option[T]` parameter and a parameter
  *   (or input case-class field) with a Scala default are optional, and every other parameter is
  *   required — the same rule the derived schema applies without `@Param`. An omitted optional
  *   argument takes `None` or the Scala default when the tool or prompt is called. An explicit
  *   `required = true` requires an `Option` or defaulted parameter anyway. `required = false` is
  *   accepted only on an `Option` or on a parameter with a default value, where it states what is
  *   already the case. On a field of a tool's OUTPUT type a default changes nothing: the field is
  *   always written, so it stays required unless `required = false` is written
  * @param schema
  *   Optional JSON schema override for the parameter type. Literal values only (`Some("...")` /
  *   `None` / a `final val`); a non-literal argument is a compile-time error. The override is
  *   ENFORCED: a `tools/call` whose value does not conform is rejected before the method runs
  *   (unless validation is turned off, `McpServerSettings.validateToolInput`). Enforcement covers
  *   `type`, `enum`, `const`, `minimum` / `maximum` and the exclusive forms, `minLength` /
  *   `maxLength`, `minItems` / `maxItems`, `items`, `properties`, `required`,
  *   `additionalProperties`, `allOf` / `anyOf` / `oneOf` / `not` and local `$ref`. It does NOT
  *   cover `pattern`, `format`, `multipleOf` or `uniqueItems` (full list:
  *   [[com.tjclp.fastmcp.core.JsonSchemaValidator]]) — those are advertised only, so check them in
  *   the method. The override replaces the whole derived property, including the `null` alternative
  *   of an `Option` parameter: write `"type": ["string", "null"]` (and `null` in an `enum`) if
  *   clients may send an explicit `null`. The `description` and `examples` of the same `@Param` are
  *   added to the fragment unless the fragment states its own. A literal that is not JSON, or is
  *   not a JSON object (or `true` / `false`), is a compile-time error. Only tools have an input
  *   schema, so this applies to `@Tool` parameters and typed-tool fields.
  * @since 0.2.1
  */
class Param(
    val description: String,
    val examples: List[String] = Nil,
    val required: Boolean = true,
    val schema: Option[String] = None
) extends StaticAnnotation

/** Marker annotation for methods representing MCP Resources. If uri contains {placeholders}, it's
  * treated as a template resource. Placeholders in the URI must match the method parameter names,
  * described with `@Param`. Static resources (no placeholders) should have methods with no
  * parameters.
  *
  * @param uri
  *   The URI or URI template for the resource (e.g., "file:///data.txt" or
  *   "users://{userId}/profile"). Template URIs are identified by their placeholder POSITIONS, not
  *   names: `users://{id}` and `users://{userId}` are the same pattern and may not both be
  *   registered from one object (compile-time error); a static URI may be registered once per
  *   object. Literal text is matched verbatim (not as a regex) and placeholders in one path segment
  *   must be separated by literal text. A template that breaks these rules (`x://{a}{b}`, a
  *   repeated placeholder name, unbalanced braces, a `/` inside a placeholder) is a compile-time
  *   error.
  * @param name
  *   Optional name for the resource (defaults to method name). Literal values only.
  * @param description
  *   Optional description for the resource (defaults to the first paragraph of the Scaladoc).
  *   Literal values only.
  * @param mimeType
  *   Optional MIME type for the resource. Literal values only. When omitted, no `mimeType` is
  *   advertised on `resources/list` or sent with a text body on `resources/read`, and a binary
  *   (`Array[Byte]`) body is served as `application/octet-stream`. (The typed contracts
  *   `McpStaticResource` / `McpTemplateResource` differ: their `mimeType` parameter defaults to
  *   `Some("text/plain")`.)
  */
class Resource(
    val uri: String,
    val name: Option[String] = None,
    val description: Option[String] = None,
    val mimeType: Option[String] = None
) extends StaticAnnotation

/** Marker annotation for methods representing MCP Prompts
  *
  * @param name
  *   Optional name for the prompt. Must be a literal `Some("...")` / `Option("...")` / `None` (or a
  *   `final val` constant) and unique per scanned object (a duplicate is a compile-time error), so
  *   overloaded annotated methods need explicit names. When omitted, the method name is used (a
  *   description-only annotation still registers under the method name).
  * @param description
  *   Optional description for the prompt (defaults to the first paragraph of the Scaladoc). Literal
  *   values only.
  */
class Prompt(
    val name: Option[String] = None,
    val description: Option[String] = None
) extends StaticAnnotation
