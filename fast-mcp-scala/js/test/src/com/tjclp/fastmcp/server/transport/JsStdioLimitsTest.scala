package com.tjclp.fastmcp.server.transport

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*

import com.tjclp.fastmcp.facades.node.NodeReadableStream
import com.tjclp.fastmcp.interop.ZioJsPromise
import com.tjclp.fastmcp.jsonrpc.JsonRpcMessage
import com.tjclp.fastmcp.server.{LimitSettings, McpServer, McpServerSettings}
import com.tjclp.fastmcp.server.router.Session
import com.tjclp.fastmcp.server.transport.JsTransportBackend.given

/** Feed the production Bun stdin callbacks, including their accumulator and whitespace handling. */
class JsStdioLimitsTest extends AsyncFlatSpec with Matchers:
  override implicit val executionContext: ExecutionContext = ExecutionContext.global

  private val limits = LimitSettings(maxFrameChars = 128)
  private val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

  private def responses(chunks: List[String], expected: Int): Task[List[JsonRpcMessage]] =
    for
      router <- McpServer("stdio-limits", "0.1.0", McpServerSettings(limits = limits)).buildRouter
      session <- Session.make("stdio-limits")
      _ <- session.markInitialized
      output <- Queue.unbounded[String]
      _ <- ZIO.succeed {
        val callbacks = js.Dictionary.empty[js.Function1[js.Any, Unit]]
        val stdin = js.Dynamic
          .literal(
            setEncoding = js.Any.fromFunction1((_: String) => ()),
            on = js.Any.fromFunction2((event: String, callback: js.Function1[js.Any, Unit]) =>
              callbacks(event) = callback
              ()
            )
          )
          .asInstanceOf[NodeReadableStream]
        JsTransportBackend.wireStdin(
          router,
          session,
          Runtime.default,
          _ => (),
          stdin,
          line => output.offer(line).unit
        )
        chunks.foreach(chunk => callbacks("data")(chunk))
      }
      replies <- ZIO
        .foreach(List.fill(expected)(()))(_ => output.take)
        .timeoutFail(new RuntimeException("missing stdio reply"))(5.seconds)
        .ensuring(session.terminate *> output.shutdown)
      parsed <- ZIO.foreach(replies)(reply =>
        ZIO.fromEither(reply.fromJson[JsonRpcMessage]).mapError(new RuntimeException(_))
      )
    yield parsed

  "Bun stdio" should "reject padded overflow and recover on the following line" in {
    val invalidLines = List(
      ping + " " * 200 + "invalid discarded tail",
      " " * 200 + ping,
      " " * 200,
      " " * (limits.maxFrameChars + 1),
      // A retained prefix ending in CR is still oversized when more characters were discarded.
      ping + " " * (limits.maxFrameChars - ping.length) + "\rdiscarded tail"
    )
    val inputs = invalidLines.flatMap { invalid =>
      val input = invalid + "\r\n" + ping + "\n"
      List(List(input), input.grouped(17).toList)
    }
    ZioJsPromise.zioToPromise(ZIO.foreach(inputs)(responses(_, 2))).toFuture.map { cases =>
      cases.foreach { replies =>
        val failures = replies.collect { case JsonRpcMessage.Failure(None, err) => err }
        failures.map(_.code) shouldBe List(-32700)
        failures.head.message should include("maxFrameChars")
        replies.count { case _: JsonRpcMessage.Success => true; case _ => false } shouldBe 1
      }
      succeed
    }
  }

  it should "accept padding at the limit with CRLF and ignore short blank lines" in {
    val padded = " " + ping + " " * (limits.maxFrameChars - ping.length - 1)
    val input = " \t\r\n" + padded + "\r\n"
    val chunks = input.grouped(17).toList
    ZioJsPromise.zioToPromise(responses(chunks, 1)).toFuture.map { replies =>
      replies match
        case List(_: JsonRpcMessage.Success) => succeed
        case other => fail(s"within-limit stdio frame was rejected: $other")
    }
  }
