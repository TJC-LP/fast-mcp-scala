package com.tjclp.fastmcp.server.router

import zio.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.ToolInputSchema
import com.tjclp.fastmcp.jsonrpc.{JsonFields, McpError}
import com.tjclp.fastmcp.server.manager.ToolManager

/** Pluggable JSON Schema validator for `tools/call` arguments.
  *
  * The seam is shared and platform-neutral; concrete implementations are opt-in. A JVM
  * implementation backed by `networknt/json-schema-validator` can be supplied without touching
  * shared code; Scala.js can plug in its own (or stay on [[SchemaValidator.permissive]]). Default
  * is permissive — validation is off unless the server wires a validator.
  */
trait SchemaValidator:
  /** Return `Left(message)` to reject the arguments, `Right(())` to accept. */
  def validate(schema: ToolInputSchema, arguments: Json): Either[String, Unit]

object SchemaValidator:
  /** Accept everything — the default when no validator is configured. */
  val permissive: SchemaValidator = (_, _) => Right(())

/** Middleware that validates `tools/call` arguments against the tool's declared input schema before
  * the handler runs. No-ops for every other method. With [[SchemaValidator.permissive]] it is a
  * pass-through, so it is always safe to install.
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
