package com.tjclp.fastmcp
package facades
package server

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Scala.js facade for the low-level MCP server from `@modelcontextprotocol/sdk/server/index.js`.
  *
  * This is the JSON-schema-first entry point (as opposed to `McpServer`, which is Zod-first). Our
  * Scala backend wraps this class so we can register handlers for each MCP method ourselves,
  * keeping full control over the tool/prompt/resource manager layer.
  */
@JSImport("@modelcontextprotocol/sdk/server/index.js", "Server")
@js.native
class Server(serverInfo: Implementation, options: js.UndefOr[ServerOptions] = js.undefined)
    extends js.Object:

  /** Register a handler for a specific request type (Zod schema with a `method` literal).
    *
    * @param requestSchema
    *   an imported Zod schema such as `CallToolRequestSchema`
    * @param handler
    *   receives the validated request + extra; returns a JS Promise of the result
    */
  def setRequestHandler(
      requestSchema: js.Any,
      handler: js.Function2[js.Dynamic, RequestHandlerExtra, js.Promise[js.Any]]
  ): Unit = js.native

  /** Merge capabilities into the server before `connect()`. */
  def registerCapabilities(capabilities: ServerCapabilities): Unit = js.native

  /** Attach a transport and start serving. Returns once the transport is up. */
  def connect(transport: Transport): js.Promise[Unit] = js.native

  /** Disconnect the transport and tear down. */
  def close(): js.Promise[Unit] = js.native

  /** Client implementation info — populated after the initialize handshake completes. */
  def getClientVersion(): js.UndefOr[Implementation] = js.native

  /** Client-declared capabilities — populated after initialize. */
  def getClientCapabilities(): js.UndefOr[js.Object] = js.native

  /** Fires when the client sends `initialized`. */
  var oninitialized: js.UndefOr[js.Function0[Unit]] = js.native

/** `{ name, version }` block passed to `new Server(...)`. */
trait Implementation extends js.Object:
  val name: String
  val version: String

object Implementation:

  def apply(name: String, version: String): Implementation =
    js.Dynamic.literal(name = name, version = version).asInstanceOf[Implementation]

/** Options passed to the `Server` constructor. */
trait ServerOptions extends js.Object:
  val capabilities: js.UndefOr[ServerCapabilities]
  val instructions: js.UndefOr[String]
  val jsonSchemaValidator: js.UndefOr[js.Any]

object ServerOptions:

  def apply(
      capabilities: js.UndefOr[ServerCapabilities] = js.undefined,
      instructions: js.UndefOr[String] = js.undefined,
      jsonSchemaValidator: js.UndefOr[js.Any] = js.undefined
  ): ServerOptions =
    js.Dynamic
      .literal(
        capabilities = capabilities,
        instructions = instructions,
        jsonSchemaValidator = jsonSchemaValidator
      )
      .asInstanceOf[ServerOptions]

/** Advertised server capabilities. Each non-empty field is an object (frequently empty `{}`) to
  * signal that the feature is supported.
  */
trait ServerCapabilities extends js.Object:
  val tools: js.UndefOr[js.Object]
  val resources: js.UndefOr[js.Object]
  val prompts: js.UndefOr[js.Object]
  val logging: js.UndefOr[js.Object]
  val tasks: js.UndefOr[js.Object]

object ServerCapabilities:

  /** Build a `ServerCapabilities` from boolean flags. Each flag controls whether the MCP feature is
    * advertised; fine-grained sub-capabilities (like `listChanged`) are not exposed yet.
    *
    * `tasks` follows the spec 2025-11-25 shape: when enabled, advertises support for the `list` /
    * `cancel` operations and for `tools/call` task augmentation.
    */
  def apply(
      tools: Boolean = false,
      resources: Boolean = false,
      prompts: Boolean = false,
      logging: Boolean = false,
      tasks: Boolean = false
  ): ServerCapabilities =
    val raw = js.Dictionary.empty[js.Any]
    if tools then raw("tools") = js.Dictionary.empty[js.Any]
    if resources then raw("resources") = js.Dictionary.empty[js.Any]
    if prompts then raw("prompts") = js.Dictionary.empty[js.Any]
    if logging then raw("logging") = js.Dictionary.empty[js.Any]
    if tasks then
      raw("tasks") = js.Dynamic
        .literal(
          list = js.Dictionary.empty[js.Any],
          cancel = js.Dictionary.empty[js.Any],
          requests = js.Dynamic.literal(
            tools = js.Dynamic.literal(call = js.Dictionary.empty[js.Any])
          )
        )
        .asInstanceOf[js.Object]
    raw.asInstanceOf[ServerCapabilities]

/** Opaque handle for any TS-SDK transport. */
@js.native
trait Transport extends js.Object

/** `extra` parameter passed to every request handler — exposes session, signal, and request info.
  */
@js.native
trait RequestHandlerExtra extends js.Object:
  val signal: js.UndefOr[js.Any] = js.native
  val sessionId: js.UndefOr[String] = js.native
  val requestId: js.UndefOr[js.Any] = js.native
  val requestInfo: js.UndefOr[js.Dynamic] = js.native
  val authInfo: js.UndefOr[js.Object] = js.native
