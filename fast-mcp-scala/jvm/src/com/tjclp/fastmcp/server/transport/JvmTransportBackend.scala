package com.tjclp.fastmcp
package server.transport

import zio.*
import zio.http.*
import zio.stream.*

import com.tjclp.fastmcp.server.McpServerSettings
import com.tjclp.fastmcp.server.router.{McpRouter, Session}

/** JVM [[TransportBackend]] — pure ZIO over `System.in`/`System.out` (stdio) and ZIO HTTP
  * (stateless). No `Unsafe`, no runtime capture, no Mono: the native router is ZIO, so `R` flows
  * straight through `Server.serve` and the user's `.provide(...)`.
  *
  * Streamable HTTP (sessions + SSE) is added in Stage D; until then `serveHttp` runs stateless.
  */
object JvmTransportBackend extends TransportBackend:

  override def serveStdio[R](router: McpRouter[R], settings: McpServerSettings): ZIO[R, Throwable, Unit] =
    for
      session <- Session.make("stdio")
      // One writer owns stdout; both replies and server-pushed outbound go through it so lines
      // never interleave.
      outLock <- Semaphore.make(1)
      emit = (line: String) => outLock.withPermit(writeLine(line))
      // Drain server-initiated messages (log/progress notifications) to stdout.
      _ <- session.outbound.take
        .flatMap(msg => emit(MessageLoop.encodeOutbound(msg)))
        .forever
        .forkDaemon
      _ <- ZStream
        .fromInputStream(java.lang.System.in)
        .via(ZPipeline.utf8Decode)
        .via(ZPipeline.splitLines)
        .map(_.trim)
        .filter(_.nonEmpty)
        .runForeach { line =>
          MessageLoop.handleFrame(router, session, line).flatMap {
            case Some(reply) => emit(reply)
            case None => ZIO.unit
          }
        }
    yield ()

  override def serveHttp[R](router: McpRouter[R], settings: McpServerSettings): ZIO[R, Throwable, Unit] =
    // Stage B: stateless request/response. Stage D branches on settings.stateless for streamable.
    // Capture the environment ZIO-natively and thread it into each handler via provideEnvironment,
    // so Routes are `Routes[Any]` — Server.serve then needs only `Server`, avoiding the generic-R
    // HasNoScope constraint. No Unsafe, no runtime capture.
    val ep = settings.httpEndpoint.stripPrefix("/")
    ZIO.environment[R].flatMap { env =>
      val routes: Routes[Any, Response] = Routes(
        Method.POST / ep -> handler { (request: Request) =>
          handleStatelessPost(router, request).provideEnvironment(env)
        },
        Method.GET / ep -> handler((_: Request) => ZIO.succeed(Response.status(Status.MethodNotAllowed))),
        Method.DELETE / ep -> handler((_: Request) => ZIO.succeed(Response.status(Status.MethodNotAllowed)))
      )
      Server
        .serve(routes)
        .provideLayer(Server.defaultWith(_.binding(settings.host, settings.port)))
        .unit
    }

  /** One stateless POST: fresh ephemeral session, dispatch a single frame, return the reply (or
    * `202 Accepted` for a notification, which produces no body).
    */
  private def handleStatelessPost[R](
      router: McpRouter[R],
      request: Request
  ): ZIO[R, Nothing, Response] =
    val effect =
      for
        body <- request.body.asString.mapError(e => Option(e.getMessage).getOrElse("body read error"))
        session <- Session.make("stateless")
        reply <- MessageLoop.handleFrame(router, session, body)
      yield reply match
        case Some(json) => Response.json(json)
        case None => Response.status(Status.Accepted)
    effect.catchAll(msg => ZIO.succeed(Response.text(msg).status(Status.BadRequest)))

  private def writeLine(line: String): Task[Unit] =
    ZIO.attempt {
      val out = java.lang.System.out
      out.print(line)
      out.print('\n')
      out.flush()
    }

  /** The JVM platform seam, in the impl object so it's exportable (givens can't be wildcard-
    * exported straight from a package). `ExportsJvm` re-exports this so `import
    * com.tjclp.fastmcp.*` puts a `TransportBackend` in scope and `McpServer(...)` resolves.
    */
  given instance: TransportBackend = this
