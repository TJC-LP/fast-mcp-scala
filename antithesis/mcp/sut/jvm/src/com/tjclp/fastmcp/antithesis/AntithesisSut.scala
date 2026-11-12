package com.tjclp.fastmcp.antithesis

import com.antithesis.sdk.Assert
import com.fasterxml.jackson.databind.node.{JsonNodeFactory, ObjectNode}
import zio.*

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.{
  HttpTransportBackend,
  JvmHttpBackend,
  JvmSocketHttpBackend
}

/** JVM launcher for the server under test. `AntithesisSut <port> <netty|socket>`.
  *
  * The assertions go through the Antithesis Java SDK, with literal messages at each call site so
  * the SDK's cataloging finds them. This module is not published and is the only place the SDK
  * is on the classpath.
  */
object AntithesisSut extends ZIOAppDefault:

  private def details(pairs: (String, String)*): ObjectNode =
    val node = JsonNodeFactory.instance.objectNode()
    pairs.foreach { case (k, v) => node.put(k, v) }
    node

  private object SdkProbe extends Probe:
    def serverStarts(boot: String, port: Int): Unit =
      Assert.reachable("the server starts", details("boot" -> boot, "port" -> port.toString))

    def handlerArgumentsSatisfySchema(ok: Boolean, color: String): Unit =
      Assert.always(
        ok,
        "a tool handler only sees arguments that satisfy its declared input schema",
        details("color" -> color)
      )

    def handlerInterrupted(): Unit =
      Assert.reachable("a handler is interrupted before it finishes", details())

  override def run =
    for
      args <- getArgs
      port = args.headOption.flatMap(_.toIntOption).getOrElse(8000)
      backend: HttpTransportBackend =
        if args.lift(1).contains("socket") then JvmSocketHttpBackend else JvmHttpBackend
      tools = new SutTools(SdkProbe, java.util.UUID.randomUUID().toString)
      server = SutTools.server(port)
      _ <- tools.register(server)
      _ <- ZIO.succeed(SdkProbe.serverStarts(tools.bootId, port))
      _ <- Console.printLine(
        s"sut: boot ${tools.bootId} listening on :$port (${args.lift(1).getOrElse("netty")})"
      )
      _ <- server.runHttp()(using backend)
    yield ()
