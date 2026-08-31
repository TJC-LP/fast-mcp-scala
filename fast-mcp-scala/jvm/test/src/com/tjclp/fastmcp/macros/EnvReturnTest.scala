package com.tjclp.fastmcp
package macros

import org.scalatest.funsuite.AnyFunSuite
import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmHttpBackend.given
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** Annotated tool handler with a `Ref[Int]` environment requirement. */
object EnvReturnToolHandlers:

  @Tool(name = Some("inc"), description = Some("Increment the counter and return the new value"))
  def inc(): ZIO[Ref[Int], Throwable, Int] =
    ZIO.serviceWithZIO[Ref[Int]](_.updateAndGet(_ + 1))

/** Annotated resource + prompt handlers with a `Ref[String]` environment requirement. */
object EnvReturnResourceHandlers:

  @Resource(uri = "env-resource://current", description = Some("Read the env-stored string"))
  def envResource(): ZIO[Ref[String], Throwable, String] =
    ZIO.serviceWithZIO[Ref[String]](_.get)

  @Prompt(name = Some("env-greeting"), description = Some("Greet using the env-stored name"))
  def envPrompt(): ZIO[Ref[String], Throwable, List[Message]] =
    ZIO
      .serviceWithZIO[Ref[String]](_.get)
      .map(name => List(Message(role = Role.User, content = TextContent(s"Hello $name"))))

/** Verifies that `@Tool` / `@Resource` / `@Prompt` macros accept ZIO returns with non-Any
  * environments (issue #55). The server's `R` flows from `McpServer.typed[R]("name")` into each
  * macro-generated handler; user-supplied layers reach the handler through the runtime captured
  * at `runHttp[R]() / runStdio[R]()` entry.
  *
  * The first three tests drive the macro's handler directly, providing `R` through the
  * `Runtime[R]` they run the effect on (built from the env layer). The fourth test asserts the
  * `runHttp()` signature compiles into a `ZIO[R, Throwable, Unit]` so users can chain
  * `.provide(...)` end-to-end.
  */
class EnvReturnTest extends AnyFunSuite:

  private def runWithRuntime[R, A](
      runtime: Runtime[R],
      effect: ZIO[R, Throwable, A]
  ): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("@Tool returning ZIO[Ref[Int], Throwable, Int] reads from the Ref") {
    val server = McpServer.typed[Ref[Int]]("EnvReturnToolServer", "0.1.0")
    server.scanAnnotations[EnvReturnToolHandlers.type]

    val runtime = Unsafe.unsafe { implicit unsafe =>
      Runtime.unsafe.fromLayer(ZLayer.fromZIO(Ref.make(0)))
    }
    val first = runWithRuntime(runtime, server.toolManager.callTool("inc", Map.empty, None))
    val second = runWithRuntime(runtime, server.toolManager.callTool("inc", Map.empty, None))
    assert(first == 1, s"expected 1 after first inc, got $first")
    assert(second == 2, s"expected 2 after second inc, got $second")
  }

  test("@Resource returning ZIO[Ref[String], Throwable, String] reads from the Ref") {
    val server = McpServer.typed[Ref[String]]("EnvReturnResourceServer", "0.1.0")
    server.scanAnnotations[EnvReturnResourceHandlers.type]

    val runtime = Unsafe.unsafe { implicit unsafe =>
      Runtime.unsafe.fromLayer(ZLayer.fromZIO(Ref.make("env-value")))
    }
    val body = runWithRuntime(
      runtime,
      server.resourceManager.readResource("env-resource://current", None)
    )
    assert(body == "env-value", s"expected env-value, got $body")
  }

  test("@Prompt returning ZIO[Ref[String], Throwable, List[Message]] reads from the Ref") {
    val server = McpServer.typed[Ref[String]]("EnvReturnPromptServer", "0.1.0")
    server.scanAnnotations[EnvReturnResourceHandlers.type]

    val runtime = Unsafe.unsafe { implicit unsafe =>
      Runtime.unsafe.fromLayer(ZLayer.fromZIO(Ref.make("greeted-name")))
    }
    val messages = runWithRuntime(
      runtime,
      server.promptManager.getPrompt("env-greeting", Map.empty, None)
    )
    assert(messages.length == 1)
    val text = messages.head.content match
      case TextContent(s, _, _) => s
      case other => fail(s"unexpected content shape: $other")
    assert(text == "Hello greeted-name")
  }

  test("runHttp[R]() returns ZIO[R, Throwable, Unit] so users can call .provide(layer)") {
    val server = McpServer.typed[Ref[Int]]("EnvReturnHttpServer", "0.1.0")
    server.scanAnnotations[EnvReturnToolHandlers.type]

    // The compile-check is the test: prove that the new API surface lets a user write
    // `server.runHttp().provide(Ref.make(0))`. We assert the static type of `server.runHttp()`
    // is `ZIO[Ref[Int], Throwable, Unit]` (so `.provide` accepts a `Ref[Int]`-providing layer)
    // without actually binding a port.
    val effect: ZIO[Ref[Int], Throwable, Unit] = server.runHttp()
    assert(effect != null) // ensure the binding survives the compiler's dead-code elimination
  }
