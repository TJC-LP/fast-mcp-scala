package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.Error

import org.scalatest.Assertions.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.router.{McpRouter, Session}
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given
import com.tjclp.fastmcp.server.transport.MessageLoop

/** Shared request/result case class for the macro DX (TJC-2331) fixtures. */
case class DxItem(name: String, qty: Int)

/** One `tools/call` reply, as a client sees it. */
final case class DxCall(isError: Boolean, texts: List[String], structuredContent: Option[Json]):
  def text: String = texts.mkString("\n")

/** Drives a server over real JSON-RPC frames — `initialize`, `tools/list`, `tools/call` — through
  * the shared [[MessageLoop]], exactly the path a stdio or HTTP client exercises. The macro DX
  * tests assert on what goes on the wire (advertised `inputSchema` vs. runtime decode), so nothing
  * here short-circuits the router.
  */
final class MacroDxHarness private (router: McpRouter[Any], session: Session):
  import MacroDxHarness.runUnsafe

  private val nextId = new java.util.concurrent.atomic.AtomicInteger(10)

  def frame(request: String): String =
    runUnsafe(MessageLoop.handleFrame(router, session, request)).getOrElse(
      fail(s"no reply to frame: $request")
    )

  private def request(method: String, params: String): Json =
    val id = nextId.incrementAndGet()
    val raw = frame(s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
    raw.fromJson[Json].fold(err => fail(s"unparseable reply <$raw>: $err"), identity)

  /** The `result.tools` array of `tools/list`. */
  def toolsList: List[Json] =
    val reply = request("tools/list", "{}")
    reply.asObject
      .flatMap(_.get("result"))
      .flatMap(_.asObject)
      .flatMap(_.get("tools"))
      .flatMap(_.asArray)
      .map(_.toList)
      .getOrElse(fail(s"tools/list carried no result.tools: $reply"))

  def toolNames: List[String] =
    toolsList.flatMap(_.asObject.flatMap(_.get("name")).flatMap(_.asString))

  /** The advertised `inputSchema` of one tool. */
  def inputSchema(tool: String): Json =
    toolsList
      .find(_.asObject.flatMap(_.get("name")).flatMap(_.asString).contains(tool))
      .flatMap(_.asObject)
      .flatMap(_.get("inputSchema"))
      .getOrElse(fail(s"tool '$tool' not advertised; tools = $toolNames"))

  /** The advertised top-level `required` list (empty when the key is absent). */
  def required(tool: String): List[String] =
    MacroDxHarness.stringArray(inputSchema(tool), "required")

  /** The advertised top-level `properties` keys, in wire order. */
  def propertyNames(tool: String): List[String] =
    inputSchema(tool).asObject
      .flatMap(_.get("properties"))
      .flatMap(_.asObject)
      .map(_.fields.map(_._1).toList)
      .getOrElse(Nil)

  /** `tools/call` with a raw JSON `arguments` object. */
  def call(tool: String, arguments: String): DxCall =
    val reply = request("tools/call", s"""{"name":"$tool","arguments":$arguments}""")
    val result = reply.asObject.flatMap(_.get("result")).flatMap(_.asObject) match
      case Some(r) => r
      case None => fail(s"tools/call '$tool' $arguments returned no result: $reply")
    val content = result.get("content").flatMap(_.asArray).map(_.toList).getOrElse(Nil)
    val texts = content.flatMap(_.asObject.flatMap(_.get("text")).flatMap(_.asString))
    DxCall(
      isError = result.get("isError").flatMap(_.asBoolean).getOrElse(false),
      texts = texts,
      structuredContent = result.get("structuredContent")
    )

  def call(tool: String, arguments: Json): DxCall = call(tool, arguments.toJson)

object MacroDxHarness:

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"macro-dx","version":"1.0"}}}"""

  def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  /** Build a server, let `register` mount tools on it (annotation scan or typed contracts), then
    * open an initialized session against its router.
    */
  def apply(name: String)(register: McpServer[Any] => Unit): MacroDxHarness =
    val server = McpServer(name)
    register(server)
    val router = runUnsafe(server.buildRouter)
    val session = runUnsafe(Session.make(s"$name-session"))
    val harness = new MacroDxHarness(router, session)
    val _ = harness.frame(initFrame)
    harness

  def stringArray(json: Json, key: String): List[String] =
    json.asObject
      .flatMap(_.get(key))
      .flatMap(_.asArray)
      .map(_.toList.flatMap(_.asString))
      .getOrElse(Nil)

  def parseJson(text: String): Either[String, Json] = text.fromJson[Json]

  /** OverloadNegativeTest-style helper for `typeCheckErrors` results. */
  def assertSomeMessageContains(errors: List[Error], fragments: String*): Unit =
    val msgs = errors.map(_.message)
    fragments.foreach { fragment =>
      assert(
        msgs.exists(_.contains(fragment)),
        s"expected a compile error containing <$fragment>; got ${msgs.size} error(s): " +
          msgs.mkString("\n---\n")
      )
    }

  def messages(errors: List[Error]): String =
    if errors.isEmpty then "(compiled cleanly)" else errors.map(_.message).mkString("\n---\n")
