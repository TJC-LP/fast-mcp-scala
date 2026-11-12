package com.tjclp.fastmcp.server

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, RequestId}
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.MessageLoop

/** `limits.maxUriChars` (TJC-2295 / F2 criterion 2): over-long client URIs are refused with -32602
  * on resources/read, subscribe and unsubscribe BEFORE the matcher, the handler or the session's
  * subscription set is touched, and dropped from subscriptions/listen.
  */
class ResourceUriLimitTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  case class AArgs(a: String)

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  /** A router with template `x://{a}` whose handler counts invocations. */
  private def fixture(
      limits: LimitSettings = LimitSettings()
  ): (McpRouter[Any], Session, AtomicInteger) =
    val counter = new AtomicInteger(0)
    val server = McpServer(
      "UriLimits",
      "0.1.0",
      McpServerSettings(resourcesSubscribe = true, limits = limits)
    )
    runUnsafe(
      server.resource(
        McpTemplateResource[AArgs](
          uriPattern = "x://{a}",
          arguments = List(ResourceArgument("a", None, required = true))
        ) { args =>
          counter.incrementAndGet()
          s"body:${args.a}"
        }
      )
    )
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make("uri-limits"))
    runUnsafe(MessageLoop.handleFrame(router, session, initFrame)).getOrElse("") should include(
      "\"serverInfo\""
    )
    (router, session, counter)

  private def call(
      router: McpRouter[Any],
      session: Session,
      id: Int,
      method: String,
      uri: String
  ): String =
    val frame =
      s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":{"uri":${Json.Str(uri).toJson}}}"""
    runUnsafe(MessageLoop.handleFrame(router, session, frame)).getOrElse(fail("no reply"))

  test("resources/read: a 9 000-char URI is -32602 (maxUriChars) and never reaches the handler") {
    val (router, session, counter) = fixture()
    val reply = call(router, session, 2, "resources/read", "x://" + "a" * 8_996)
    reply should include("-32602")
    reply should include("maxUriChars")
    reply should include("resources/read")
    counter.get() shouldBe 0

    // Exactly at the bound (8 192 chars) the URI is matched and read normally.
    val ok = call(router, session, 3, "resources/read", "x://" + "a" * 8_188)
    ok should include("body:")
    ok should not include "-32602"
    counter.get() shouldBe 1
  }

  test("resources/subscribe and unsubscribe refuse over-long URIs before touching the session") {
    val (router, session, _) = fixture()
    val long = "x://" + "s" * 9_000
    val sub = call(router, session, 4, "resources/subscribe", long)
    sub should include("-32602")
    sub should include("maxUriChars")
    runUnsafe(session.isSubscribed(long)) shouldBe false

    val unsub = call(router, session, 5, "resources/unsubscribe", long)
    unsub should include("-32602")
    unsub should include("maxUriChars")

    // A normal subscribe still works.
    call(router, session, 6, "resources/subscribe", "x://fine") should include(""""result":{}""")
    runUnsafe(session.isSubscribed("x://fine")) shouldBe true
  }

  test("subscriptions/listen acknowledges without an over-long resourceSubscriptions entry") {
    val (router, _, _) = fixture()
    val session = runUnsafe(Session.make("modern-uri-limits"))
    val long = "x://" + "z" * 9_000
    val params =
      s"""{"notifications":{"resourceSubscriptions":[${Json
          .Str(long)
          .toJson},"x://ok"]},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}"""
        .fromJson[Json]
        .fold(error => fail(error), identity)
    val request =
      JsonRpcMessage.Request(RequestId.StrId("listen-1"), "subscriptions/listen", Some(params))
    val ack = runUnsafe(
      for
        fiber <- router.dispatch(session, request).fork
        acknowledgement <- session.outbound.take
        _ <- fiber.interrupt
      yield acknowledgement
    ).toJson
    ack should include("notifications/subscriptions/acknowledged")
    ack should not include ("z" * 100)
  }

  test("a custom maxUriChars is honoured exactly (16 accepted, 17 rejected)") {
    val (router, session, counter) = fixture(LimitSettings(maxUriChars = 16))
    val sixteen = "x://" + "a" * 12
    val seventeen = "x://" + "a" * 13
    sixteen.length shouldBe 16
    seventeen.length shouldBe 17
    call(router, session, 7, "resources/read", sixteen) should include("body:")
    counter.get() shouldBe 1
    val rejected = call(router, session, 8, "resources/read", seventeen)
    rejected should include("-32602")
    rejected should include("limits.maxUriChars (16 characters)")
    counter.get() shouldBe 1
  }

  test(
    "resources/subscribe: maxSubscriptionsPerSession bounds DISTINCT URIs; re-subscribing is free"
  ) {
    val (router, session, _) = fixture(LimitSettings(maxSubscriptionsPerSession = 2))
    call(router, session, 9, "resources/subscribe", "x://one") should include(""""result":{}""")
    call(router, session, 10, "resources/subscribe", "x://two") should include(""""result":{}""")
    runUnsafe(session.subscriptionCount) shouldBe 2

    // Third distinct URI: refused, and the set is untouched.
    val third = call(router, session, 11, "resources/subscribe", "x://three")
    third should include("-32602")
    third should include("limits.maxSubscriptionsPerSession = 2")
    runUnsafe(session.isSubscribed("x://three")) shouldBe false
    runUnsafe(session.subscriptionCount) shouldBe 2

    // Re-subscribing a URI the session already holds is still OK at the cap.
    call(router, session, 12, "resources/subscribe", "x://one") should include(""""result":{}""")
    runUnsafe(session.subscriptionCount) shouldBe 2

    // Unsubscribing frees a slot for a new distinct URI.
    call(router, session, 13, "resources/unsubscribe", "x://two") should include(""""result":{}""")
    call(router, session, 14, "resources/subscribe", "x://three") should include(""""result":{}""")
    runUnsafe(session.isSubscribed("x://three")) shouldBe true
  }

  test("concurrent distinct subscriptions cannot exceed the session cap") {
    val (router, _, _) = fixture(LimitSettings(maxSubscriptionsPerSession = 1))
    runUnsafe(ZIO.foreachDiscard(1 to 16) { round =>
      for
        session <- Session.make(s"concurrent-subscriptions-$round")
        _ <- MessageLoop.handleFrame(router, session, initFrame)
        gate <- Promise.make[Nothing, Unit]
        fibers <- ZIO.foreach(1 to 128) { id =>
          val frame =
            s"""{"jsonrpc":"2.0","id":$id,"method":"resources/subscribe","params":{"uri":"x://$id"}}"""
          (gate.await *> MessageLoop.handleFrame(router, session, frame)).fork
        }
        _ <- gate.succeed(())
        replies <- ZIO.foreach(fibers)(_.join)
        count <- session.subscriptionCount
        _ <- ZIO.succeed {
          count shouldBe 1
          replies.count(_.exists(_.contains("\"result\":{}"))) shouldBe 1
          replies.count(_.exists(_.contains("-32602"))) shouldBe 127
        }
        _ <- session.terminate
      yield ()
    })
  }
