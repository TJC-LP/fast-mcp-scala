package com.tjclp.fastmcp
package server.transport

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import reactor.core.publisher.Mono
import zio.*
import zio.http.*

/** ZIO HTTP-backed implementation of [[McpStatelessServerTransport]].
  *
  * Each incoming HTTP POST is independently dispatched to the Java SDK's
  * [[McpStatelessServerHandler]] which handles all JSON-RPC routing (initialize, tools/list,
  * tools/call, etc.). No session state is maintained between requests.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Var"))
class ZioHttpStatelessTransport(
    jsonMapper: McpJsonMapper,
    endpoint: String = "/mcp"
) extends McpStatelessServerTransport:

  @volatile private var mcpHandler: McpStatelessServerHandler = scala.compiletime.uninitialized
  @volatile private var isClosing: Boolean = false

  override def setMcpHandler(handler: McpStatelessServerHandler): Unit =
    mcpHandler = handler

  override def closeGracefully(): Mono[Void] =
    Mono.fromRunnable[Void](() => { isClosing = true })

  /** Build zio-http [[Routes]] that delegate to the SDK handler. */
  def routes: Routes[Any, Response] =
    val path = Method.POST / endpoint.stripPrefix("/")
    val getPath = Method.GET / endpoint.stripPrefix("/")
    Routes(
      path -> handler { (request: Request) => handlePost(request) },
      getPath -> handler { (_: Request) =>
        ZIO.succeed(Response.status(Status.MethodNotAllowed))
      }
    )

  private def handlePost(request: Request): ZIO[Any, Nothing, Response] =
    if isClosing then
      ZIO.succeed(
        Response
          .text("Server is shutting down")
          .status(Status.ServiceUnavailable)
      )
    else
      val result = for {
        // Validate Accept header per MCP spec
        accept <- ZIO
          .fromOption(request.header(Header.Accept).map(_.renderedValue))
          .orElseFail("Missing Accept header")
        _ <- ZIO.when(
          !(accept.contains("application/json") && accept.contains("text/event-stream"))
        )(
          ZIO.fail(
            "Both application/json and text/event-stream required in Accept header"
          )
        )
        body <- request.body.asString.mapError(_.getMessage)
        transportContext = extractTransportContext(request)
        message <- ZIO
          .attempt(McpSchema.deserializeJsonRpcMessage(jsonMapper, body))
          .mapError(_.getMessage)
        response <- dispatchMessage(transportContext, message)
      } yield response

      result.catchAll { errorMsg =>
        ZIO.succeed(errorResponse(Status.BadRequest, errorMsg))
      }

  private def dispatchMessage(
      transportContext: McpTransportContext,
      message: McpSchema.JSONRPCMessage
  ): ZIO[Any, String, Response] =
    message match
      case jsonrpcRequest: McpSchema.JSONRPCRequest =>
        monoToZio(
          mcpHandler
            .handleRequest(transportContext, jsonrpcRequest)
            .contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext))
        ).mapBoth(
          e => s"Failed to handle request: ${e.getMessage}",
          jsonrpcResponse => {
            val jsonText = jsonMapper.writeValueAsString(jsonrpcResponse)
            Response
              .text(jsonText)
              .addHeader(Header.ContentType(MediaType.application.json))
              .status(Status.Ok)
          }
        )

      case jsonrpcNotification: McpSchema.JSONRPCNotification =>
        monoToZio(
          mcpHandler
            .handleNotification(transportContext, jsonrpcNotification)
            .contextWrite(ctx => ctx.put(McpTransportContext.KEY, transportContext))
        ).mapBoth(
          e => s"Failed to handle notification: ${e.getMessage}",
          _ => Response.status(Status.Accepted)
        )

      case _ =>
        ZIO.succeed(
          errorResponse(
            Status.BadRequest,
            "The server accepts either requests or notifications"
          )
        )

  private def extractTransportContext(request: Request): McpTransportContext =
    val metadata = new java.util.HashMap[String, Object]()
    request.headers.foreach { h =>
      val _ = metadata.put(h.headerName, h.renderedValue)
    }
    McpTransportContext.create(
      java.util.Collections.unmodifiableMap(metadata)
    )

  private def errorResponse(status: Status, message: String): Response =
    Response
      .text(s"""{"error":"$message"}""")
      .addHeader(Header.ContentType(MediaType.application.json))
      .status(status)

  private def monoToZio[A](mono: Mono[A]): ZIO[Any, Throwable, A] =
    ZIO.fromCompletionStage(mono.toFuture())

end ZioHttpStatelessTransport
