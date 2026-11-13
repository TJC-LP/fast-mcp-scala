package com.tjclp.fastmcp.examples.bench

import zio.*

import com.tjclp.fastmcp.{*, given}

/** The server the benchmark harness in `bench/` drives, identical on every platform: two typed
  * tools and nothing else. `add` is the smallest useful call (a few hundred bytes each way), so it
  * measures per-request overhead; `echo` returns its argument, so a request of N bytes gets a reply
  * of about N bytes and measures payload handling.
  *
  * Settings match a small deployment rather than a best case: the DNS-rebinding guard is on, the
  * input limits are the defaults, logging and tasks are off. The platform launchers
  * (`BenchServerJvm`, `BenchServerJs`, `BenchServerNative`) differ only in the HTTP backend.
  */
object BenchServer:

  val Name = "fast-mcp-scala-bench"
  val Version = "1.0.0"
  val DefaultPort = 18077

  case class AddArgs(a: Int, b: Int)
  case class EchoArgs(text: String)

  /** `stateless` selects the legacy adapter's sessionless JSON mode; 2026-07-28 requests are
    * stateless either way.
    */
  def settings(port: Int, stateless: Boolean = false): McpServerSettings =
    McpServerSettings(
      host = "127.0.0.1",
      port = port,
      httpEndpoint = "/mcp",
      stateless = stateless,
      // Host/Origin validation, as a deployed server runs it (and as a loopback bind gets by
      // default), so the benchmark measures the guarded request path.
      allowedHosts = Some(Set("127.0.0.1", "localhost"))
    )

  val tools: List[McpTool[?, ?]] = List(
    McpTool[AddArgs, Int](name = "add", description = Some("Add two integers")) { args =>
      args.a + args.b
    },
    McpTool[EchoArgs, String](name = "echo", description = Some("Return the text unchanged")) {
      args => args.text
    }
  )

  def register(server: McpServer[Any]): ZIO[Any, Throwable, Unit] =
    ZIO.foreachDiscard(tools)(server.tool(_))
