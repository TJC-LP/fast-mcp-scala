package com.tjclp.fastmcp.antithesis

import java.util.concurrent.atomic.AtomicLong

import com.antithesis.sdk.Assert
import com.fasterxml.jackson.databind.node.{JsonNodeFactory, ObjectNode}
import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{TaskSupport, ToolInputSchema}
import com.tjclp.fastmcp.server.TaskSettings
import com.tjclp.fastmcp.server.transport.{
  HttpTransportBackend,
  JvmHttpBackend,
  JvmSocketHttpBackend
}

/** The server the Antithesis workload in `antithesis/mcp` tests: the library's HTTP transport with
  * a handful of tools shaped so the driver can judge every reply.
  *
  * `AntithesisSut <port> <netty|socket>`
  *
  * Every tool takes a `token` the driver draws fresh for each call and returns it, so a reply can
  * be matched to exactly one request. Assertions placed here (the Antithesis Java SDK) cover what
  * no reply shows: what a handler was handed, and which rare branches ran. This module is not
  * published and is the only place the SDK is on the classpath.
  */
object AntithesisSut extends ZIOAppDefault:

  /** Changes on every start, so the driver can tell a restarted node from a live one. */
  private val bootId: String = java.util.UUID.randomUUID().toString

  private val started = new AtomicLong(0)
  private val finished = new AtomicLong(0)
  private val interrupted = new AtomicLong(0)

  /** Header the driver sets to a fresh value on every request (`contextHeaders`). */
  val UserHeader = "x-test-user"

  private def details(pairs: (String, String)*): ObjectNode =
    val node = JsonNodeFactory.instance.objectNode()
    pairs.foreach { case (k, v) => node.put(k, v) }
    node

  case class TokenArgs(token: String)
  case class ProgressArgs(token: String, steps: Int)
  case class SlowArgs(token: String, millis: Int)
  case class ColorArgs(token: String, color: String)
  given JsonDecoder[ColorArgs] = DeriveJsonDecoder.gen[ColorArgs]

  private val Colors = Set("red", "green", "blue")

  /** Run a handler body while counting it: `started - finished - interrupted` is the number of
    * handlers still running, which must return to zero once requests stop.
    */
  private def counted[A](body: ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A] =
    ZIO.succeed(started.incrementAndGet()) *>
      body.onExit {
        case Exit.Success(_) => ZIO.succeed(finished.incrementAndGet())
        case Exit.Failure(cause) if cause.isInterruptedOnly =>
          ZIO.succeed {
            interrupted.incrementAndGet()
            Assert.reachable("a handler is interrupted before it finishes", details())
          }
        case Exit.Failure(_) => ZIO.succeed(finished.incrementAndGet())
      }

  private val echo = McpTool[TokenArgs, String](
    name = "echo",
    description = Some("Return the token")
  ).contextual((args, _) => counted(ZIO.succeed(s"echo:${args.token}")))

  private val whoami = McpTool[TokenArgs, String](
    name = "whoami",
    description = Some("Report the request context this call was handed")
  ).contextual { (args, context) =>
    counted(ZIO.succeed {
      val ctx = context.getOrElse(McpContext.empty)
      val user = ctx.header(UserHeader).getOrElse("-")
      val session = ctx.protocolSessionId.getOrElse("-")
      s"whoami:${args.token}:user=$user:session=$session:boot=$bootId"
    })
  }

  private val progress = McpTool[ProgressArgs, String](
    name = "progress",
    description = Some("Emit `steps` progress notifications, then return the token")
  ).contextual { (args, context) =>
    val steps = math.max(0, math.min(args.steps, 16))
    val emit = context.flatMap(ctx => ctx.progressToken.map(token => (ctx, token))) match
      case Some((ctx, token)) =>
        ZIO.foreachDiscard(1 to steps)(i => ctx.sendProgress(token, i.toDouble, Some(steps.toDouble)))
      case None => ZIO.unit
    counted(emit.as(s"progress:${args.token}:$steps"))
  }

  private val slow = McpTool[SlowArgs, String](
    name = "slow",
    description = Some("Stay silent for `millis`, then return the token")
  ).contextual { (args, _) =>
    val millis = math.max(0, math.min(args.millis, 5000))
    counted(ZIO.sleep(millis.millis).as(s"slow:${args.token}"))
  }

  private val taskEcho = McpTool[SlowArgs, String](
    name = "task_echo",
    description = Some("Like slow, and may run as a task")
  ).contextual { (args, _) =>
    val millis = math.max(0, math.min(args.millis, 5000))
    counted(ZIO.sleep(millis.millis).as(s"task:${args.token}"))
  }.withTaskSupport(TaskSupport.Optional)

  private val color = McpTool
    .withSchema[ColorArgs, String](
      name = "color",
      inputSchema = ToolInputSchema.unsafeFromJsonString(
        """{"type":"object","properties":{"token":{"type":"string"},"color":{"type":"string","enum":["red","green","blue"]}},"required":["token","color"],"additionalProperties":false}"""
      ),
      description = Some("Accepts only the colours its schema lists")
    )
    .contextual { (args, _) =>
      counted(ZIO.succeed {
        // Claude Security finding "Declared tool input-schema constraints are advertised but
        // never enforced on tools/call": the handler must never see a value outside the enum.
        Assert.always(
          Colors.contains(args.color),
          "a tool handler only sees arguments that satisfy its declared input schema",
          details("color" -> args.color.take(64))
        )
        s"color:${args.token}:${args.color.take(64)}"
      })
    }

  /** Counters for the driver's checks once faults stop. Not counted itself. */
  private val stats = McpTool[TokenArgs, String](
    name = "stats",
    description = Some("Handler counters and the boot id")
  ).contextual { (args, _) =>
    ZIO.succeed {
      val s = started.get(); val f = finished.get(); val i = interrupted.get()
      s"stats:${args.token}:boot=$bootId:started=$s:finished=$f:interrupted=$i:running=${s - f - i}"
    }
  }

  private def settings(port: Int): McpServerSettings =
    McpServerSettings(
      host = "0.0.0.0",
      port = port,
      httpEndpoint = "/mcp",
      // The names the driver and the health check reach this container by.
      allowedHosts = Some(Set("mcp-netty", "mcp-socket", "localhost", "127.0.0.1")),
      keepAliveInterval = Some(java.time.Duration.ofMillis(500)),
      contextHeaders = Set(UserHeader),
      loggingEnabled = true,
      // Small enough that the driver's sessions reach the cap and eviction runs.
      maxSessions = Some(32),
      tasks = TaskSettings(enabled = true)
    )

  override def run =
    for
      args <- getArgs
      port = args.headOption.flatMap(_.toIntOption).getOrElse(8000)
      backend: HttpTransportBackend =
        if args.lift(1).contains("socket") then JvmSocketHttpBackend else JvmHttpBackend
      server = McpServer("fast-mcp-scala-antithesis", "1.0.0", settings(port))
      _ <- ZIO.foreachDiscard(List(echo, whoami, progress, slow, taskEcho, color, stats))(
        server.tool(_)
      )
      _ <- ZIO.succeed(
        Assert.reachable("the server starts", details("boot" -> bootId, "port" -> port.toString))
      )
      _ <- Console.printLine(s"sut: boot $bootId listening on :$port (${args.lift(1).getOrElse("netty")})")
      _ <- server.runHttp()(using backend)
    yield ()
