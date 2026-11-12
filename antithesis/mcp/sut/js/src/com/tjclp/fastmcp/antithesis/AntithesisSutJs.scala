package com.tjclp.fastmcp.antithesis

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel, JSImport}

import zio.{Runtime, Unsafe}

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.server.transport.*

@js.native
@JSImport("node:fs", JSImport.Namespace)
private object NodeFs extends js.Object:
  def appendFileSync(path: String, data: String): Unit = js.native

/** Bun entry for the server under test, exported as `startAntithesisSut` (the same arrangement
  * as `startConformance`).
  *
  * There is no Antithesis SDK for this runtime, so assertions are written in the SDK's fallback
  * format: one JSON object per line, appended to `$ANTITHESIS_OUTPUT_DIR/sdk.jsonl` (or to
  * `$ANTITHESIS_SDK_LOCAL_OUTPUT` for the local loop). Each assertion is declared once at startup
  * with `hit: false`, so one that is never reached still shows up as a property.
  */
object AntithesisSutJs:

  private def env(name: String): Option[String] =
    js.Dynamic.global.process.env.selectDynamic(name).asInstanceOf[js.UndefOr[String]].toOption

  private val output: Option[String] =
    env("ANTITHESIS_SDK_LOCAL_OUTPUT").orElse(env("ANTITHESIS_OUTPUT_DIR").map(_ + "/sdk.jsonl"))

  private def emit(
      assertType: String,
      display: String,
      message: String,
      hit: Boolean,
      condition: Boolean,
      mustHit: Boolean,
      details: js.Dictionary[js.Any]
  ): Unit =
    output.foreach { path =>
      val record = js.Dynamic.literal(
        antithesis_assert = js.Dynamic.literal(
          id = message,
          message = message,
          condition = condition,
          display_type = display,
          hit = hit,
          must_hit = mustHit,
          assert_type = assertType,
          location = js.Dynamic.literal(
            file = "AntithesisSutJs.scala",
            function = "probe",
            `class` = "com.tjclp.fastmcp.antithesis.AntithesisSutJs",
            begin_line = 0,
            begin_column = 0
          ),
          details = details
        )
      )
      // A failed write must never take the server down with it.
      try NodeFs.appendFileSync(path, js.JSON.stringify(record) + "\n")
      catch case _: Throwable => ()
    }

  private val StartsMsg = "the Bun server starts"
  private val SchemaMsg =
    "a tool handler on Bun only sees arguments that satisfy its declared input schema"
  private val InterruptedMsg = "a handler on Bun is interrupted before it finishes"

  private object JsonlProbe extends Probe:
    def serverStarts(boot: String, port: Int): Unit =
      emit("reachability", "Reachable", StartsMsg, hit = true, condition = true, mustHit = true,
        js.Dictionary[js.Any]("boot" -> boot, "port" -> port))

    def handlerArgumentsSatisfySchema(ok: Boolean, color: String): Unit =
      emit("always", "Always", SchemaMsg, hit = true, condition = ok, mustHit = true,
        js.Dictionary[js.Any]("color" -> color))

    def handlerInterrupted(): Unit =
      emit("reachability", "Reachable", InterruptedMsg, hit = true, condition = true,
        mustHit = true, js.Dictionary[js.Any]())

  /** The catalog: every assertion declared before it can be hit. */
  private def declare(): Unit =
    val none = js.Dictionary[js.Any]()
    emit("reachability", "Reachable", StartsMsg, hit = false, condition = false, mustHit = true, none)
    emit("always", "Always", SchemaMsg, hit = false, condition = false, mustHit = true, none)
    emit("reachability", "Reachable", InterruptedMsg, hit = false, condition = false,
      mustHit = true, none)

  @JSExportTopLevel("startAntithesisSut")
  def start(port: Int): Unit =
    declare()
    val boot = js.Dynamic.global.crypto.randomUUID().asInstanceOf[String]
    val tools = new SutTools(JsonlProbe, boot)
    val server = SutTools.server(port)
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(tools.register(server)).getOrThrowFiberFailure()
    }
    val _ = server.startStatefulHttp()
    JsonlProbe.serverStarts(tools.bootId, port)
    println(s"sut: boot ${tools.bootId} listening on :$port (bun)")
