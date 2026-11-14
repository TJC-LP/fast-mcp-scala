package com.tjclp.fastmcp.server

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.funsuite.AsyncFunSuite
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.MessageLoop

/** An `McpServerApp` that answers `completion/complete`, written with the root import only. */
object CompletingApp extends McpServerApp[Stdio, CompletingApp.type]:

  private val languages = List("scala", "scalajs", "kotlin")

  @Prompt(name = Some("greet"))
  def greet(@Param("Language") lang: String): String = s"Greet in $lang"

  override def completion: Option[CompletionHandler[Any]] = Some { (request, _) =>
    ZIO.succeed(Completion(languages.filter(_.startsWith(request.argument.value))))
  }

/** The same app without a provider: `completion` keeps its default. */
object PlainApp extends McpServerApp[Stdio, PlainApp.type]:

  @Prompt(name = Some("greet"))
  def greet(@Param("Language") lang: String): String = s"Greet in $lang"

/** `McpServerApp.completion` registers a `completion/complete` provider (GH #115, D6.9). Until
  * 1.1.0 the trait had no such member and `buildCore` is final, so a declarative server could not
  * answer completion requests. Compiled into the JVM, Scala.js and Scala Native test modules.
  */
class McpServerAppCompletionSpec extends AsyncFunSuite:

  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private def run[A](effect: ZIO[Any, Throwable, A]): Future[A] =
    Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.runToFuture(effect))

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private val completeFrame =
    """{"jsonrpc":"2.0","id":2,"method":"completion/complete","params":{"ref":{"type":"ref/prompt","name":"greet"},"argument":{"name":"lang","value":"sca"}}}"""

  /** The replies to `initialize` and to one `completion/complete`, on the server `app` builds. */
  private def replies(app: McpServerApp[?, ?]): ZIO[Any, Throwable, (String, String)] =
    for
      core <- app.buildCore
      server <- core match
        case s: McpServer[Any] @unchecked => ZIO.succeed(s)
        case other => ZIO.fail(new AssertionError(s"unexpected core: $other"))
      router <- server.buildRouter
      session <- Session.make("completion-app")
      init <- MessageLoop.handleFrame(router, session, initFrame)
      complete <- MessageLoop.handleFrame(router, session, completeFrame)
    yield (init.getOrElse(""), complete.getOrElse(""))

  test("an overridden `completion` is registered: capability advertised, requests answered") {
    run(replies(CompletingApp)).map { (init, complete) =>
      assert(init.contains(""""completions":{}"""), init)
      assert(complete.contains(""""values":["scala","scalajs"]"""), complete)
    }
  }

  test("the default registers nothing: no capability, and the method is not found") {
    run(replies(PlainApp)).map { (init, complete) =>
      assert(!init.contains("completions"), init)
      assert(complete.contains("-32601"), complete)
    }
  }
