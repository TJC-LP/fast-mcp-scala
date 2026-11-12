package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.ToolInputSchema
import com.tjclp.fastmcp.jsonrpc.{JsonFields, McpError}
import com.tjclp.fastmcp.server.manager.ToolManager

/** An ADDITIONAL, router-level check of `tools/call` arguments — not the library's input-schema
  * enforcement.
  *
  * Enforcement of a tool's advertised `inputSchema` is built in and on by default: it happens in
  * `ToolManager.callTool`, before the handler, with the dependency-free
  * [[com.tjclp.fastmcp.core.JsonSchemaValidator]] on every platform, and is controlled by
  * `McpServerSettings.validateToolInput` and `ToolRegistrationOptions.validateInputSchema`.
  *
  * This trait is a separate seam for code that assembles a router itself through
  * [[RouterBuilder.build]] (it is not reachable from `McpServer` / `McpServerApp`): a validator
  * passed there runs first, in [[ValidationMiddleware]], and a rejection is a JSON-RPC `-32602`
  * protocol error rather than the `isError` tool result the built-in check produces. The default is
  * [[SchemaValidator.permissive]], which adds nothing.
  */
trait SchemaValidator:
  /** Return `Left(message)` to reject the arguments, `Right(())` to accept. */
  def validate(schema: ToolInputSchema, arguments: Json): Either[String, Unit]

object SchemaValidator:
  /** Accept everything — the default: no check beyond the built-in one in `ToolManager`. */
  val permissive: SchemaValidator = (_, _) => Right(())

/** Middleware that rejects a `tools/call` for an unknown tool and runs the router-level
  * [[SchemaValidator]] (see there; the built-in input-schema enforcement is in `ToolManager`, not
  * here). No-ops for every other method. With [[SchemaValidator.permissive]] only the unknown-tool
  * check remains, so it is always safe to install.
  */
final class ValidationMiddleware[R](
    validator: SchemaValidator,
    toolManager: ToolManager[R]
) extends Middleware[R]:

  def wrap(method: String, next: RequestHandler[R]): RequestHandler[R] =
    if method != Methods.ToolsCall then next
    else
      (session, params) =>
        val check: Either[McpError, Unit] =
          for
            name <- params match
              case Json.Obj(fields) =>
                JsonFields.get(fields, "name") match
                  case Some(Json.Str(n)) => Right(n)
                  case _ => Left(McpError.invalidParams("tools/call: missing `name`"))
              case _ => Left(McpError.invalidParams("tools/call: params must be an object"))
            schema <- toolManager
              .getToolDefinition(name)
              .map(_.inputSchema)
              .toRight(McpError.invalidParams(s"Unknown tool: $name"))
            args = JsonFields.get(params, "arguments").getOrElse(Json.Obj())
            _ <- validator.validate(schema, args).left.map(McpError.invalidParams)
          yield ()
        ZIO.fromEither(check) *> next(session, params)
