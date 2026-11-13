package com.tjclp.fastmcp.antithesis

import java.util.concurrent.atomic.AtomicLong

import zio.*
import zio.json.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.core.{TaskSupport, ToolInputSchema}
import com.tjclp.fastmcp.examples.conformance.ConformanceServer
import com.tjclp.fastmcp.server.TaskSettings

/** Assertions the server under test makes about itself. Each method is one Antithesis property;
  * the platform launchers implement them with what the platform has (the Java SDK on the JVM,
  * the fallback JSON-lines format on Bun).
  */
trait Probe:
  /** Reached once at startup. */
  def serverStarts(boot: String, port: Int): Unit

  /** Must hold on every `color` call: the handler was handed a value its schema allows. */
  def handlerArgumentsSatisfySchema(ok: Boolean, color: String): Unit

  /** Reached when a handler is interrupted before it finishes. */
  def handlerInterrupted(): Unit

/** The server the Antithesis workload in `antithesis/mcp` tests, identical on every platform: the
  * library's HTTP transport with a handful of tools shaped so the driver can judge every reply.
  *
  * Every tool takes a `token` the driver draws fresh for each call and returns it, so a reply can
  * be matched to exactly one request. This code is not published.
  */
final class SutTools(probe: Probe, val bootId: String):
  // `bootId` changes on every start, so the driver can tell a restarted node from a live one. The
  // launcher supplies it: there is no `java.util.UUID.randomUUID` on Scala.js.

  private val started = new AtomicLong(0)
  private val finished = new AtomicLong(0)
  private val interrupted = new AtomicLong(0)

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
            probe.handlerInterrupted()
          }
        case Exit.Failure(_) => ZIO.succeed(finished.incrementAndGet())
      }

  private val echo = McpTool[SutTools.TokenArgs, String](
    name = "echo",
    description = Some("Return the token")
  ).contextual((args, _) => counted(ZIO.succeed(s"echo:${args.token}")))

  private val whoami = McpTool[SutTools.TokenArgs, String](
    name = "whoami",
    description = Some("Report the request context this call was handed")
  ).contextual { (args, context) =>
    counted(ZIO.succeed {
      val ctx = context.getOrElse(McpContext.empty)
      val user = ctx.header(SutTools.UserHeader).getOrElse("-")
      val session = ctx.protocolSessionId.getOrElse("-")
      s"whoami:${args.token}:user=$user:session=$session:boot=$bootId"
    })
  }

  private val progress = McpTool[SutTools.ProgressArgs, String](
    name = "progress",
    description = Some("Emit `steps` progress notifications, then return the token")
  ).contextual { (args, context) =>
    val steps = math.max(0, math.min(args.steps, 16))
    val emit = context.flatMap(ctx => ctx.progressToken.map(token => (ctx, token))) match
      case Some((ctx, token)) =>
        ZIO.foreachDiscard(1 to steps)(i =>
          ctx.sendProgress(token, i.toDouble, Some(steps.toDouble))
        )
      case None => ZIO.unit
    counted(emit.as(s"progress:${args.token}:$steps"))
  }

  private val slow = McpTool[SutTools.SlowArgs, String](
    name = "slow",
    description = Some("Stay silent for `millis`, then return the token")
  ).contextual { (args, _) =>
    val millis = math.max(0, math.min(args.millis, 5000))
    counted(ZIO.sleep(millis.millis).as(s"slow:${args.token}"))
  }

  private val taskEcho = McpTool[SutTools.SlowArgs, String](
    name = "task_echo",
    description = Some("Like slow, and may run as a task")
  ).contextual { (args, _) =>
    val millis = math.max(0, math.min(args.millis, 5000))
    counted(ZIO.sleep(millis.millis).as(s"task:${args.token}"))
  }.withTaskSupport(TaskSupport.Optional)

  private val color = McpTool
    .withSchema[SutTools.ColorArgs, String](
      name = "color",
      inputSchema = ToolInputSchema.unsafeFromJsonString(
        """{"type":"object","properties":{"token":{"type":"string"},"color":{"type":"string","enum":["red","green","blue"]}},"required":["token","color"],"additionalProperties":false}"""
      ),
      description = Some("Accepts only the colours its schema lists")
    )
    .contextual { (args, _) =>
      counted(ZIO.succeed {
        // The handler must never see a value outside the enum its schema declares.
        probe.handlerArgumentsSatisfySchema(Colors.contains(args.color), args.color.take(64))
        s"color:${args.token}:${args.color.take(64)}"
      })
    }

  /** Counters for the driver's checks once faults stop. Not counted itself. */
  private val stats = McpTool[SutTools.TokenArgs, String](
    name = "stats",
    description = Some("Handler counters and the boot id")
  ).contextual { (args, _) =>
    ZIO.succeed {
      val s = started.get(); val f = finished.get(); val i = interrupted.get()
      s"stats:${args.token}:boot=$bootId:started=$s:finished=$f:interrupted=$i:running=${s - f - i}"
    }
  }

  /** The workload's own tools, and next to them the fixture of the official conformance harness
    * (`ConformanceServer`: tools named `test_*`, prompts, resources, a completion handler). The
    * driver runs the harness against this same server once faults have stopped, so the fixture
    * has to live on the server that went through them. No name is shared between the two sets.
    */
  def register(server: McpServer[Any]): ZIO[Any, Throwable, Unit] =
    ZIO.foreachDiscard(List(echo, whoami, progress, slow, taskEcho, color, stats))(
      server.tool(_)
    ) *> ConformanceServer.register(server)

object SutTools:

  /** Header the driver sets to a fresh value on every request (`contextHeaders`). */
  val UserHeader = "x-test-user"

  case class TokenArgs(token: String)
  case class ProgressArgs(token: String, steps: Int)
  case class SlowArgs(token: String, millis: Int)
  case class ColorArgs(token: String, color: String)
  given JsonDecoder[ColorArgs] = DeriveJsonDecoder.gen[ColorArgs]

  def settings(port: Int): McpServerSettings =
    McpServerSettings(
      host = "0.0.0.0",
      port = port,
      httpEndpoint = "/mcp",
      // The names the driver and the health check reach the containers by.
      allowedHosts = Some(Set("mcp-netty", "mcp-socket", "mcp-bun", "localhost", "127.0.0.1")),
      keepAliveInterval = Some(java.time.Duration.ofMillis(500)),
      contextHeaders = Set(UserHeader),
      loggingEnabled = true,
      // The harness's resources-subscribe and resources-unsubscribe scenarios need it
      // (`ConformanceServer.settings` sets it too).
      resourcesSubscribe = true,
      // Small enough that the driver's floods reach the cap and eviction runs.
      maxSessions = Some(32),
      tasks = TaskSettings(enabled = true)
    )

  def server(port: Int): McpServer[Any] =
    McpServer("fast-mcp-scala-antithesis", "1.0.0", settings(port))
