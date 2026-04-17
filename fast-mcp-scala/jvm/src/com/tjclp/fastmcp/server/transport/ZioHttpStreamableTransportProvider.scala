package com.tjclp.fastmcp
package server.transport

import java.lang.System as JSystem
import java.util.concurrent.ConcurrentHashMap

import scala.annotation.unused
import scala.jdk.CollectionConverters.*

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.HttpHeaders
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStreamableServerSession
import io.modelcontextprotocol.spec.McpStreamableServerTransport
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider
import io.modelcontextprotocol.spec.ProtocolVersions
import reactor.core.publisher.Mono
import zio.*
import zio.http.*
import zio.stream.*

/** ZIO HTTP-backed implementation of [[McpStreamableServerTransportProvider]].
  *
  * Provides full Streamable HTTP transport per the MCP spec: session management via
  * `mcp-session-id` header, SSE streaming for server-to-client messages, and POST/GET/DELETE
  * endpoint handling.
  *
  * Each SSE stream is backed by a [[ZioHttpStreamableSessionTransport]] that bridges the SDK's
  * Reactor-based `sendMessage` calls to a ZIO Queue for zio-http's pull-based streaming.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Var"))
class ZioHttpStreamableTransportProvider(
    jsonMapper: McpJsonMapper,
    endpoint: String = "/mcp",
    disallowDelete: Boolean = false,
    @unused
    keepAliveInterval: Option[java.time.Duration] = None
) extends McpStreamableServerTransportProvider:

  @volatile private var sessionFactory: McpStreamableServerSession.Factory =
    scala.compiletime.uninitialized
  @volatile private var isClosing: Boolean = false

  private val sessions = new ConcurrentHashMap[String, McpStreamableServerSession]()

  override def setSessionFactory(factory: McpStreamableServerSession.Factory): Unit =
    sessionFactory = factory

  override def protocolVersions(): java.util.List[String] =
    java.util.List.of(
      ProtocolVersions.MCP_2024_11_05,
      ProtocolVersions.MCP_2025_03_26,
      ProtocolVersions.MCP_2025_06_18,
      ProtocolVersions.MCP_2025_11_25
    )

  override def notifyClients(method: String, params: Object): Mono[Void] =
    Mono.fromRunnable[Void] { () =>
      sessions.values().asScala.foreach { session =>
        try session.sendNotification(method, params).block()
        catch
          case e: Exception =>
            JSystem.err.println(
              s"[FastMCPScala] Failed to notify session ${session.getId}: ${e.getMessage}"
            )
      }
    }

  override def closeGracefully(): Mono[Void] =
    Mono.fromRunnable[Void] { () =>
      isClosing = true
      sessions.values().asScala.foreach { session =>
        try session.closeGracefully().block()
        catch
          case e: Exception =>
            JSystem.err.println(
              s"[FastMCPScala] Failed to close session ${session.getId}: ${e.getMessage}"
            )
      }
      sessions.clear()
    }

  /** Build zio-http [[Routes]] that handle POST, GET, and DELETE for the MCP endpoint. */
  def routes: Routes[Any, Response] =
    val postPath = Method.POST / endpoint.stripPrefix("/")
    val getPath = Method.GET / endpoint.stripPrefix("/")
    val deletePath = Method.DELETE / endpoint.stripPrefix("/")
    Routes(
      postPath -> handler { (request: Request) => handlePost(request) },
      getPath -> handler { (request: Request) => handleGet(request) },
      deletePath -> handler { (request: Request) => handleDelete(request) }
    )

  // ---- POST handler ----

  private def handlePost(request: Request): ZIO[Any, Nothing, Response] =
    if isClosing then
      ZIO.succeed(errorResponse(Status.ServiceUnavailable, "Server is shutting down"))
    else
      val result = for {
        accept <- ZIO
          .fromOption(request.header(Header.Accept).map(_.renderedValue))
          .orElseFail("Missing Accept header")
        _ <- ZIO.when(!accept.contains("text/event-stream"))(
          ZIO.fail("text/event-stream required in Accept header")
        )
        _ <- ZIO.when(!accept.contains("application/json"))(
          ZIO.fail("application/json required in Accept header")
        )
        body <- request.body.asString.mapError(_.getMessage)
        message <- ZIO
          .attempt(McpSchema.deserializeJsonRpcMessage(jsonMapper, body))
          .mapError(_.getMessage)
        response <- dispatchPost(request, message)
      } yield response

      result.catchAll { errorMsg =>
        ZIO.succeed(errorResponse(Status.BadRequest, errorMsg))
      }

  private def dispatchPost(
      request: Request,
      message: McpSchema.JSONRPCMessage
  ): ZIO[Any, String, Response] =
    val transportContext = extractTransportContext(request)

    message match
      // 1. Initialize request — no session required
      case jsonrpcRequest: McpSchema.JSONRPCRequest
          if jsonrpcRequest.method() == McpSchema.METHOD_INITIALIZE =>
        ZIO
          .attempt {
            val initRequest = jsonMapper.convertValue(
              jsonrpcRequest.params(),
              new TypeRef[McpSchema.InitializeRequest]() {}
            )
            val init = sessionFactory.startSession(initRequest)
            val session = init.session()
            sessions.put(session.getId, session)

            val initResult = init
              .initResult()
              .contextWrite(ctx =>
                ctx.put(
                  io.modelcontextprotocol.common.McpTransportContext.KEY,
                  transportContext
                )
              )
              .block()

            val jsonResponse = jsonMapper.writeValueAsString(
              new McpSchema.JSONRPCResponse(
                McpSchema.JSONRPC_VERSION,
                jsonrpcRequest.id(),
                initResult,
                null
              )
            )

            Response
              .text(jsonResponse)
              .addHeader(Header.ContentType(MediaType.application.json))
              .addHeader(Header.Custom(HttpHeaders.MCP_SESSION_ID, session.getId))
              .status(Status.Ok)
          }
          .mapError(e => s"Failed to initialize session: ${e.getMessage}")

      // All other messages require a session
      case _ =>
        val sessionId = request.rawHeader(HttpHeaders.MCP_SESSION_ID)
        sessionId match
          case None =>
            ZIO.succeed(
              errorResponse(
                Status.BadRequest,
                s"Session ID required in ${HttpHeaders.MCP_SESSION_ID} header"
              )
            )
          case Some(sid) =>
            Option(sessions.get(sid)) match
              case None =>
                ZIO.succeed(
                  errorResponse(Status.NotFound, s"Session not found: $sid")
                )
              case Some(session) =>
                handleSessionPost(session, message, transportContext)

  private def handleSessionPost(
      session: McpStreamableServerSession,
      message: McpSchema.JSONRPCMessage,
      transportContext: io.modelcontextprotocol.common.McpTransportContext
  ): ZIO[Any, String, Response] =
    message match
      // 2. JSON-RPC Request — return SSE stream with response
      case jsonrpcRequest: McpSchema.JSONRPCRequest =>
        for {
          transport <- ZioHttpStreamableSessionTransport.make(
            session.getId,
            jsonMapper
          )
          // Run the SDK's response stream synchronously. This calls the handler, pushes the
          // SSE event to the queue via sendMessage, then shuts down the queue via closeGracefully.
          // The queue is already populated when we build the SSE response.
          _ <- ZIO
            .attemptBlocking(
              session
                .responseStream(jsonrpcRequest, transport)
                .contextWrite(ctx =>
                  ctx.put(
                    io.modelcontextprotocol.common.McpTransportContext.KEY,
                    transportContext
                  )
                )
                .block()
            )
            .mapError(e => s"Failed to process request: ${e.getMessage}")
        } yield Response.fromServerSentEvents(transport.sseStream)

      // 3. JSON-RPC Notification — 202
      case jsonrpcNotification: McpSchema.JSONRPCNotification =>
        ZIO
          .attempt(
            session
              .accept(jsonrpcNotification)
              .contextWrite(ctx =>
                ctx.put(
                  io.modelcontextprotocol.common.McpTransportContext.KEY,
                  transportContext
                )
              )
              .block()
          )
          .as(Response.status(Status.Accepted))
          .mapError(e => s"Failed to handle notification: ${e.getMessage}")

      // 4. JSON-RPC Response (client responding to server-initiated request) — 202
      case jsonrpcResponse: McpSchema.JSONRPCResponse =>
        ZIO
          .attempt(
            session
              .accept(jsonrpcResponse)
              .contextWrite(ctx =>
                ctx.put(
                  io.modelcontextprotocol.common.McpTransportContext.KEY,
                  transportContext
                )
              )
              .block()
          )
          .as(Response.status(Status.Accepted))
          .mapError(e => s"Failed to handle response: ${e.getMessage}")

      case _ =>
        ZIO.succeed(
          errorResponse(Status.BadRequest, "Unknown message type")
        )

  // ---- GET handler (SSE listening stream) ----

  private def handleGet(request: Request): ZIO[Any, Nothing, Response] =
    if isClosing then
      ZIO.succeed(errorResponse(Status.ServiceUnavailable, "Server is shutting down"))
    else
      val result = for {
        accept <- ZIO
          .fromOption(request.header(Header.Accept).map(_.renderedValue))
          .orElseFail("text/event-stream required in Accept header")
        _ <- ZIO.when(!accept.contains("text/event-stream"))(
          ZIO.fail("text/event-stream required in Accept header")
        )
        sid <- ZIO
          .fromOption(request.rawHeader(HttpHeaders.MCP_SESSION_ID))
          .orElseFail(s"Session ID required in ${HttpHeaders.MCP_SESSION_ID} header")
        session <- ZIO
          .fromOption(Option(sessions.get(sid)))
          .orElseFail("Session not found")
        transport <- ZioHttpStreamableSessionTransport
          .make(sid, jsonMapper)
        _ <- ZIO
          .attempt {
            // Establish live stream for server-initiated messages.
            val _ = session.listeningStream(transport)

            // If the client provides Last-Event-ID, replay missed messages first.
            request.rawHeader(HttpHeaders.LAST_EVENT_ID).foreach { lastId =>
              val transportContext = extractTransportContext(request)
              session
                .replay(lastId)
                .contextWrite(ctx =>
                  ctx.put(
                    io.modelcontextprotocol.common.McpTransportContext.KEY,
                    transportContext
                  )
                )
                .toIterable
                .forEach { message =>
                  val _ = transport.sendMessage(message).block()
                }
            }
          }
          .mapError(_.getMessage)
      } yield Response.fromServerSentEvents(transport.sseStream)

      result.catchAll { errorMsg =>
        ZIO.succeed(errorResponse(Status.BadRequest, errorMsg))
      }

  // ---- DELETE handler (session termination) ----

  private def handleDelete(request: Request): ZIO[Any, Nothing, Response] =
    if isClosing then
      ZIO.succeed(errorResponse(Status.ServiceUnavailable, "Server is shutting down"))
    else if disallowDelete then ZIO.succeed(Response.status(Status.MethodNotAllowed))
    else
      val result = for {
        sid <- ZIO
          .fromOption(request.rawHeader(HttpHeaders.MCP_SESSION_ID))
          .orElseFail(s"Session ID required in ${HttpHeaders.MCP_SESSION_ID} header")
        session <- ZIO
          .fromOption(Option(sessions.get(sid)))
          .orElseFail("Session not found")
        _ <- ZIO
          .attempt {
            val transportContext = extractTransportContext(request)
            session
              .delete()
              .contextWrite(ctx =>
                ctx.put(
                  io.modelcontextprotocol.common.McpTransportContext.KEY,
                  transportContext
                )
              )
              .block()
            val _ = sessions.remove(sid)
          }
          .mapError(_.getMessage)
      } yield Response.status(Status.Ok)

      result.catchAll {
        case msg if msg.contains("Session not found") =>
          ZIO.succeed(errorResponse(Status.NotFound, msg))
        case errorMsg =>
          ZIO.succeed(errorResponse(Status.BadRequest, errorMsg))
      }

  // ---- Utilities ----

  private def extractTransportContext(
      request: Request
  ): io.modelcontextprotocol.common.McpTransportContext =
    val metadata = new java.util.HashMap[String, Object]()
    request.headers.foreach { h =>
      val _ = metadata.put(h.headerName, h.renderedValue)
    }
    io.modelcontextprotocol.common.McpTransportContext.create(
      java.util.Collections.unmodifiableMap(metadata)
    )

  private def errorResponse(status: Status, message: String): Response =
    Response
      .text(s"""{"error":"$message"}""")
      .addHeader(Header.ContentType(MediaType.application.json))
      .status(status)

end ZioHttpStreamableTransportProvider

/** Per-SSE-stream transport that bridges the SDK's Reactor-based `sendMessage` to a ZIO Queue.
  *
  * The SDK pushes messages via `sendMessage()`; zio-http pulls them from the exposed `sseStream`.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Var"))
private[transport] class ZioHttpStreamableSessionTransport private (
    sessionId: String,
    jsonMapper: McpJsonMapper,
    queue: Queue[Option[ServerSentEvent[String]]]
) extends McpStreamableServerTransport:

  @volatile private var closed: Boolean = false

  /** The SSE stream to return as an HTTP response.
    *
    * Terminates when a `None` sentinel is observed (via [[close]] or [[closeGracefully]]). This
    * preserves already-queued events so the final JSON-RPC response is not lost when the SDK calls
    * `sendMessage(...).then(closeGracefully())`.
    */
  def sseStream: ZStream[Any, Nothing, ServerSentEvent[String]] =
    ZStream.fromQueue(queue).takeWhile(_.nonEmpty).collect { case Some(event) =>
      event
    }

  override def sendMessage(message: McpSchema.JSONRPCMessage): Mono[Void] =
    sendMessage(message, sessionId)

  override def sendMessage(message: McpSchema.JSONRPCMessage, messageId: String): Mono[Void] =
    Mono.fromRunnable[Void] { () =>
      if !closed then
        val json = jsonMapper.writeValueAsString(message)
        val event = ServerSentEvent(
          data = json,
          eventType = Some("message"),
          id = Some(Option(messageId).getOrElse(sessionId))
        )
        Unsafe.unsafe { implicit unsafe =>
          val _ = Runtime.default.unsafe.run(queue.offer(Some(event))).getOrThrowFiberFailure()
        }
    }

  override def unmarshalFrom[T](data: Object, typeRef: TypeRef[T]): T =
    jsonMapper.convertValue(data, typeRef)

  override def closeGracefully(): Mono[Void] =
    Mono.fromRunnable[Void] { () =>
      close()
    }

  override def close(): Unit =
    if !closed then
      closed = true
      Unsafe.unsafe { implicit unsafe =>
        val _ = Runtime.default.unsafe.run(queue.offer(None)).getOrThrowFiberFailure()
      }

end ZioHttpStreamableSessionTransport

private[transport] object ZioHttpStreamableSessionTransport:

  def make(
      sessionId: String,
      jsonMapper: McpJsonMapper
  ): ZIO[Any, Nothing, ZioHttpStreamableSessionTransport] =
    for {
      q <- Queue.unbounded[Option[ServerSentEvent[String]]]
    } yield new ZioHttpStreamableSessionTransport(sessionId, jsonMapper, q)
