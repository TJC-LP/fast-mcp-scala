package com.tjclp.fastmcp.server

import java.lang.ref.WeakReference

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.{*, given}
import com.tjclp.fastmcp.testkit.Eventually
import com.tjclp.fastmcp.core.{ProgressToken, TaskSupport, Tasks}
import com.tjclp.fastmcp.core.wire.Implementation
import com.tjclp.fastmcp.jsonrpc.{JsonRpcMessage, RequestId}
import com.tjclp.fastmcp.server.manager.{
  PoolKind,
  RetainedBytes,
  TaskManager,
  TaskScope,
  TaskStoreStats
}
import com.tjclp.fastmcp.server.router.{McpRouter, RouterBuilder, Session}
import com.tjclp.fastmcp.server.transport.MessageLoop

/** What a task may keep alive, and for how long.
  *
  *   - a finished task's store entry keeps neither its fiber nor anything of the request;
  *   - a running task's request is measured, capped and charged to its owner and pool;
  *   - the bytes the store retains are bounded by `TaskSettings`, whatever clients send and tools
  *     return.
  *
  * Reachability is checked with weak references and explicit collections; byte bounds are checked
  * both against the store's own accounting and against the results actually still retrievable.
  */
class TaskMemoryBoundsTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  private case class NoArgs()

  private val KiB = 1024L

  /** Did the collector clear `weak`? Asks for a collection until it does (bounded). */
  private def collected(weak: WeakReference[?], attempts: Int = 100): Boolean =
    (1 to attempts).exists { _ =>
      java.lang.System.gc()
      Thread.sleep(20)
      Option(weak.get).isEmpty
    }

  private def awaitStats(tm: TaskManager[Any])(p: TaskStoreStats => Boolean): TaskStoreStats =
    runUnsafe(Eventually("the task store reaches the expected state")(tm.stats)(p))

  private val initFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""

  private def modernMeta(extra: (String, Json)*): Json =
    Json.Obj(
      (List(
        "io.modelcontextprotocol/protocolVersion" -> Json.Str("2026-07-28"),
        "io.modelcontextprotocol/clientCapabilities" -> Json.Obj(
          "extensions" -> Json.Obj(Tasks.ExtensionId -> Json.Obj())
        )
      ) ++ extra)*
    )

  private def modernParams(tool: String, extraMeta: (String, Json)*): Json =
    Json.Obj("name" -> Json.Str(tool), "arguments" -> Json.Obj(), "_meta" -> modernMeta(extraMeta*))

  private def legacyParams(tool: String, extra: (String, Json)*): Json =
    Json.Obj(
      (List[(String, Json)](
        "name" -> Json.Str(tool),
        "arguments" -> Json.Obj(),
        "task" -> Json.Obj("ttl" -> Json.Num(60000))
      ) ++ extra)*
    )

  private def call(router: McpRouter[Any], session: Session, id: Int, params: Json): String =
    runUnsafe(
      router.dispatch(
        session,
        JsonRpcMessage.Request(RequestId.NumId(id.toLong), "tools/call", Some(params))
      )
    ).map(_.toJson).getOrElse(fail("no response"))

  private def frame(router: McpRouter[Any], session: Session, value: String): String =
    runUnsafe(MessageLoop.handleFrame(router, session, value)).getOrElse(fail("no response"))

  private val TaskIdPattern = """"taskId":"([^"]+)"""".r

  private def taskIdOf(body: String): String =
    TaskIdPattern.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(s"no taskId in: $body"))

  /** `chars` characters of filler as one JSON string (charged about `2 * chars` bytes). */
  private def filler(chars: Int): Json = Json.Str("x" * chars)

  /** A server with `quick` (returns at once), `blocky` (parks on `gate`), `big` (returns `bigChars`
    * characters) and `chatty` (emits `chatter` progress notifications), all task-capable, plus a
    * router over a TaskManager the test keeps a handle on.
    */
  private def build(
      settings: TaskSettings,
      gate: Promise[Nothing, Unit],
      bigChars: Int = 0,
      chatter: Int = 0,
      // `true`: build the manager the way `McpServer` does (`TaskManager.make`).
      viaMake: Boolean = false
  ): (McpRouter[Any], TaskManager[Any]) =
    val server = McpServer.typed[Any]("MemServer", "0.1.0", McpServerSettings(tasks = settings))
    runUnsafe(
      server.tool(
        McpTool[NoArgs, String](name = "quick")(_ => ZIO.succeed("done"))
          .withTaskSupport(TaskSupport.Optional)
      ) *>
        server.tool(
          McpTool[NoArgs, String](name = "blocky")(_ => gate.await.as("blocky"))
            .withTaskSupport(TaskSupport.Optional)
        ) *>
        server.tool(
          McpTool[NoArgs, String](name = "big")(_ => ZIO.succeed("y" * bigChars))
            .withTaskSupport(TaskSupport.Optional)
        ) *>
        server.tool(
          McpTool[NoArgs, String](name = "chatty")
            .contextual { (_, ctx) =>
              ZIO
                .foreachDiscard(1 to chatter)(i =>
                  ctx.fold(ZIO.unit)(_.sendProgress(ProgressToken.StringToken("t"), i.toDouble))
                )
                .as("chatty")
            }
            .withTaskSupport(TaskSupport.Optional)
        )
    )
    val newId = ZIO.succeed(java.util.UUID.randomUUID().toString)
    val tm =
      if viaMake then runUnsafe(TaskManager.make[Any](settings, newId))
      else TaskManager.makeUnsafe[Any](settings, newId)
    val router = RouterBuilder.build[Any](
      serverInfo = Implementation(name = "MemServer", version = "0.1.0"),
      instructions = None,
      toolManager = server.toolManager,
      promptManager = server.promptManager,
      resourceManager = server.resourceManager,
      settings = server.settings,
      taskManager = Some(tm)
    )
    (router, tm)

  private def initialized(router: McpRouter[Any], id: String): Session =
    val session = runUnsafe(Session.make(id))
    frame(router, session, initFrame) should include(""""tasks"""")
    session

  // ---------------------------------------------------------------------------------------------
  // The link the finding marked as inferred: does a held handle of a COMPLETED fiber keep the
  // FiberRef values that fiber inherited reachable? (It does — `FiberRuntime._fiberRefs` is never
  // cleared, because `Fiber#inheritAll` reads it after the fiber is done.)
  // ---------------------------------------------------------------------------------------------

  private final class Handle(var fiber: Option[Fiber.Runtime[?, ?]])

  /** Fork a fiber that inherits a FiberRef holding a fresh payload, let it finish, and hand back
    * its handle plus a weak reference to the payload. Nothing else references the payload.
    */
  private def finishedFiberWithPayload(): (Handle, WeakReference[AnyRef]) =
    val ref = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(Option.empty[AnyRef]))
    val payload = new Array[Byte](1 << 20)
    val weak = new WeakReference[AnyRef](payload)
    val fiber = runUnsafe(ref.locally(Some(payload))(ZIO.unit.forkDaemon).tap(_.await))
    (new Handle(Some(fiber)), weak)

  test("ZIO: a held handle of a finished fiber keeps its inherited FiberRef values reachable") {
    val (handle, weak) = finishedFiberWithPayload()
    // Strongly reachable through the handle: no number of collections may clear it.
    collected(weak, attempts = 10) shouldBe false
    handle.fiber.isDefined shouldBe true
    // Dropping the handle is what releases it.
    handle.fiber = None
    collected(weak) shouldBe true
  }

  // ---------------------------------------------------------------------------------------------
  // (a) A terminal store entry keeps neither the fiber nor anything of the request.
  // ---------------------------------------------------------------------------------------------

  /** Create a modern task whose `_meta` carries a fresh marker node, and return a weak reference
    * to that node. The request, its session and the marker are all local to this method.
    */
  private def modernTaskWithMarker(
      router: McpRouter[Any],
      tool: String,
      markerChars: Int
  ): (String, WeakReference[Json]) =
    val marker: Json = Json.Str(new String(Array.fill(markerChars)('m')))
    val weak = new WeakReference[Json](marker)
    val session = runUnsafe(Session.makeRequestScoped("req-marker", clientKey = Some("M")))
    val body = call(router, session, 1, modernParams(tool, "example.com/marker" -> marker))
    body should include(""""resultType":"task"""")
    (taskIdOf(body), weak)

  /** Two things used to lead from the manager back to the request: the finished task's fiber handle
    * in its store entry, and the sweeper fiber — forked by this very request (the store was empty)
    * and alive for as long as the entry is stored — which had inherited the request's FiberRefs.
    */
  private def finishedTaskKeepsNothingOfItsRequest(viaMake: Boolean): Unit =
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val (router, tm) = build(TaskSettings(enabled = true), gate, viaMake = viaMake)
    try
      val (taskId, weak) = modernTaskWithMarker(router, "quick", markerChars = 256 * 1024)
      val _ = awaitStats(tm)(s => s.total == 1 && s.running == 0)
      // The entry is still stored (and its result still retrievable), the sweeper still runs ...
      runUnsafe(tm.get(taskId, None)).map(_.status.toString) shouldBe Some("Completed")
      runUnsafe(tm.stats).sweeperActive shouldBe true
      // ... yet nothing reachable from the manager leads back to the request metadata.
      withClue("the request's _meta is still reachable after the task finished: ") {
        collected(weak) shouldBe true
      }
      val stats = runUnsafe(tm.stats)
      stats.total shouldBe 1
      stats.heldFibers shouldBe 0
    finally runUnsafe(tm.shutdown)

  test("a finished modern task's store entry keeps nothing of the request's _meta") {
    finishedTaskKeepsNothingOfItsRequest(viaMake = false)
  }

  test("... nor does the sweeper its request started (manager built by TaskManager.make)") {
    finishedTaskKeepsNothingOfItsRequest(viaMake = true)
  }

  test("a running task's request is released as soon as the task is cancelled") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val (router, tm) = build(TaskSettings(enabled = true), gate, viaMake = true)
    try
      val (taskId, weak) = modernTaskWithMarker(router, "blocky", markerChars = 64 * 1024)
      // Running: the task legitimately keeps its request (a handler may read `_meta`).
      runUnsafe(tm.stats).heldFibers shouldBe 1
      collected(weak, attempts = 5) shouldBe false
      runUnsafe(tm.cancel(taskId, None)).isRight shouldBe true
      val _ = awaitStats(tm)(s => s.total == 1 && s.running == 0)
      collected(weak) shouldBe true
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  test("what a finished task is charged does not depend on the size of the request's _meta") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val (router, tm) = build(TaskSettings(enabled = true), gate)
    try
      val small = runUnsafe(Session.makeRequestScoped("r1", clientKey = Some("small")))
      val large = runUnsafe(Session.makeRequestScoped("r2", clientKey = Some("large")))
      call(router, small, 1, modernParams("quick")) should include(""""resultType":"task"""")
      call(router, large, 2, modernParams("quick", "example.com/pad" -> filler(200_000))) should
        include(""""resultType":"task"""")
      val stats = awaitStats(tm)(s => s.total == 2 && s.running == 0)
      val smallBytes = stats.perOwner(Some("client:small")).bytes
      val largeBytes = stats.perOwner(Some("client:large")).bytes
      smallBytes should be > 0L
      largeBytes shouldBe smallBytes
      stats.heldFibers shouldBe 0
    finally runUnsafe(tm.shutdown)
  }

  test("cancelled and failed tasks also drop their fiber") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val tm = TaskManager.makeUnsafe[Any](
      TaskSettings(enabled = true),
      ZIO.succeed(java.util.UUID.randomUUID().toString)
    )
    try
      val scope = TaskScope.bearer(Some("k"))
      val running = runUnsafe(tm.create(scope, None, gate.await, _ => ZIO.unit))
      val _ = runUnsafe(tm.create(scope, None, ZIO.fail(new RuntimeException("boom")), _ => ZIO.unit))
      runUnsafe(tm.stats).heldFibers should be <= 2
      runUnsafe(tm.cancel(running.task.taskId, None)).isRight shouldBe true
      val stats = awaitStats(tm)(_.running == 0)
      stats.total shouldBe 2
      stats.heldFibers shouldBe 0
    finally runUnsafe(tm.shutdown)
  }

  // ---------------------------------------------------------------------------------------------
  // (b) A running task's request is measured, capped and charged.
  // ---------------------------------------------------------------------------------------------

  test("a modern request too large to keep for a running task is refused, and nothing is stored") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val (router, tm) = build(TaskSettings(enabled = true, maxRequestBytes = 64 * KiB), gate)
    try
      val session = runUnsafe(Session.makeRequestScoped("r", clientKey = Some("A")))
      val refused =
        call(router, session, 1, modernParams("blocky", "example.com/pad" -> filler(100_000)))
      refused should include(""""code":-32602""")
      refused should include("maxRequestBytes")
      runUnsafe(tm.stats).total shouldBe 0
      // `inputResponses` are kept for the task too, so they count.
      val viaInputs = modernParams("blocky").asObject.fold(fail("not an object"))(o =>
        Json.Obj((o.fields.toList :+ ("inputResponses" -> Json.Obj("k" -> filler(100_000))))*)
      )
      call(router, session, 2, viaInputs) should include(""""code":-32602""")
      runUnsafe(tm.stats).total shouldBe 0
      // An ordinary request still becomes a task.
      call(router, session, 3, modernParams("blocky")) should include(""""resultType":"task"""")
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  test("a legacy task-augmented request too large to keep is refused the same way") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val (router, tm) = build(TaskSettings(enabled = true, maxRequestBytes = 64 * KiB), gate)
    try
      val session = initialized(router, "legacy-big")
      val refused = call(router, session, 2, legacyParams("blocky", "pad" -> filler(100_000)))
      refused should include(""""code":-32602""")
      refused should include("maxRequestBytes")
      runUnsafe(tm.stats).total shouldBe 0
      call(router, session, 3, legacyParams("blocky")) should include(""""taskId"""")
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  test("a running task is charged for the request it keeps, within maxRequestBytes") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val settings = TaskSettings(enabled = true, maxRequestBytes = 256 * KiB)
    val (router, tm) = build(settings, gate)
    try
      val session = runUnsafe(Session.makeRequestScoped("r", clientKey = Some("A")))
      val params = modernParams("blocky", "example.com/pad" -> filler(50_000))
      call(router, session, 1, params) should include(""""resultType":"task"""")
      val charged = runUnsafe(tm.stats).perOwner(Some("client:A")).bytes
      charged should be >= RetainedBytes.ofJson(params)
      charged should be <= settings.maxRequestBytes
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  test("running tasks of one owner pin at most maxBytesPerOwner of request data") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val settings = TaskSettings(
      enabled = true,
      maxRequestBytes = 128 * KiB,
      maxResultBytes = 128 * KiB,
      maxBytesPerOwner = 350 * KiB,
      maxBytesTotal = 4096 * KiB
    )
    val (router, tm) = build(settings, gate)
    try
      val session = runUnsafe(Session.makeRequestScoped("r", clientKey = Some("A")))
      // ~100 KiB each: three fit in 350 KiB, the fourth does not — far below the count caps.
      val replies = (1 to 6).map(i =>
        call(router, session, i, modernParams("blocky", "example.com/pad" -> filler(50_000)))
      )
      replies.count(_.contains(""""resultType":"task"""")) shouldBe 3
      val refused = replies.filter(_.contains(""""code":-32003"""))
      refused should have size 3
      refused.head should include("maxBytesPerOwner")
      val stats = runUnsafe(tm.stats)
      stats.running shouldBe 3
      stats.perOwner(Some("client:A")).bytes should be <= settings.maxBytesPerOwner
      // Another owner is unaffected.
      val other = runUnsafe(Session.makeRequestScoped("r2", clientKey = Some("B")))
      call(router, other, 9, modernParams("blocky")) should include(""""resultType":"task"""")
    finally
      runUnsafe(gate.succeed(()))
      runUnsafe(tm.shutdown)
  }

  // ---------------------------------------------------------------------------------------------
  // (c) The bytes the store retains are bounded by configuration.
  // ---------------------------------------------------------------------------------------------

  test("a result larger than maxResultBytes is not kept: the task ends failed") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val settings = TaskSettings(enabled = true, maxResultBytes = 64 * KiB)
    val (router, tm) = build(settings, gate, bigChars = 100_000)
    try
      val session = runUnsafe(Session.makeRequestScoped("r", clientKey = Some("A")))
      val created = call(router, session, 1, modernParams("big"))
      val taskId = taskIdOf(created)
      val stats = awaitStats(tm)(s => s.total == 1 && s.running == 0)
      stats.perOwner(Some("client:A")).bytes shouldBe 0L
      val snapshot = frame(
        router,
        session,
        s"""{"jsonrpc":"2.0","id":2,"method":"tasks/get","params":{"taskId":"$taskId","_meta":${modernMeta().toJson}}}"""
      )
      snapshot should include(""""status":"failed"""")
      snapshot should include("maxResultBytes")
      snapshot.length should be < 4096
    finally runUnsafe(tm.shutdown)
  }

  /** Estimated bytes of the results still retrievable for `ids` (what the store really retains). */
  private def retainedResultBytes(
      tm: TaskManager[Any],
      ids: Seq[String],
      sessionId: Option[String]
  ): (Int, Long) =
    val kept = ids.flatMap { id =>
      runUnsafe(tm.snapshot(id, sessionId)).flatMap(_.outcome).collect { case Exit.Success(v) =>
        RetainedBytes.of(v)
      }
    }
    (kept.size, kept.sum)

  private def resultOf(chars: Int): Json = Json.Obj("text" -> Json.Str("r" * chars))

  /** Create a task returning `chars` characters and wait until it is terminal. */
  private def finished(tm: TaskManager[Any], scope: TaskScope, chars: Int): String =
    val created = runUnsafe(tm.create(scope, None, ZIO.succeed(resultOf(chars)), _ => ZIO.unit))
    val id = created.task.taskId
    val _ = runUnsafe(
      Eventually("the task is terminal")(tm.get(id, scope.sessionId))(_.forall(_.status.isTerminal))
    )
    id

  private def byteBounded(minResultRetentionMs: Long): TaskSettings =
    TaskSettings(
      enabled = true,
      maxConcurrentPerSession = 64,
      maxStoredPerOwner = 64,
      maxStoredTotal = 1024,
      minResultRetentionMs = minResultRetentionMs,
      maxRequestBytes = 64 * KiB,
      maxResultBytes = 64 * KiB,
      maxBytesPerOwner = 256 * KiB,
      maxBytesTotal = 1024 * KiB
    )

  test("one owner filling every stored slot retains at most maxBytesPerOwner (oldest evicted)") {
    val settings = byteBounded(minResultRetentionMs = 0L)
    val tm = TaskManager.makeUnsafe[Any](settings, ZIO.succeed(java.util.UUID.randomUUID().toString))
    try
      val scope = TaskScope.bearer(Some("A"))
      // 64 results of ~40 KiB each = ~2.5 MiB offered against a 256 KiB owner budget.
      val ids = (1 to 64).map(_ => finished(tm, scope, chars = 20_000))
      val stats = runUnsafe(tm.stats)
      stats.perOwner(scope.ownerKey).bytes should be <= settings.maxBytesPerOwner
      val (kept, bytes) = retainedResultBytes(tm, ids, None)
      bytes should be <= settings.maxBytesPerOwner
      kept should be >= 1
      kept should be <= 6
      // Past the grace the newest result is the one kept.
      runUnsafe(tm.snapshot(ids.last, None)).flatMap(_.outcome).exists(_.isSuccess) shouldBe true
      runUnsafe(tm.get(ids.head, None)) shouldBe None
    finally runUnsafe(tm.shutdown)
  }

  test("inside the retention grace an over-budget result is discarded, never an older one evicted") {
    val settings = byteBounded(minResultRetentionMs = 600_000L)
    val tm = TaskManager.makeUnsafe[Any](settings, ZIO.succeed(java.util.UUID.randomUUID().toString))
    try
      val scope = TaskScope.session("s1")
      val ids = (1 to 20).map(_ => finished(tm, scope, chars = 20_000))
      val stats = runUnsafe(tm.stats)
      stats.total shouldBe 20
      stats.perOwner(scope.ownerKey).bytes should be <= settings.maxBytesPerOwner
      val (kept, bytes) = retainedResultBytes(tm, ids, scope.sessionId)
      bytes should be <= settings.maxBytesPerOwner
      kept should be >= 1
      // The first result is still there; a later one ended failed with its result discarded.
      runUnsafe(tm.snapshot(ids.head, scope.sessionId))
        .flatMap(_.outcome)
        .exists(_.isSuccess) shouldBe true
      val last = runUnsafe(tm.get(ids.last, scope.sessionId)).getOrElse(fail("entry missing"))
      last.status.toString shouldBe "Failed"
      last.statusMessage.getOrElse("") should include("maxBytesPerOwner")
      // `tasks/result` answers an error frame for it rather than hanging.
      val exit = Unsafe.unsafe(implicit u =>
        Runtime.default.unsafe.run(tm.result(ids.last, scope.sessionId))
      )
      exit.isFailure shouldBe true
    finally runUnsafe(tm.shutdown)
  }

  test("many owners together retain at most maxBytesTotal in their pool") {
    val settings = byteBounded(minResultRetentionMs = 0L)
    val tm = TaskManager.makeUnsafe[Any](settings, ZIO.succeed(java.util.UUID.randomUUID().toString))
    try
      // 16 owners x 6 results of ~40 KiB = ~3.8 MiB offered against a 1 MiB pool budget (each
      // owner stays inside its own 256 KiB budget, so only the pool budget can hold this back).
      val owners = (1 to 16).map(i => TaskScope.bearer(Some(s"owner-$i")))
      val ids = for
        round <- 1 to 6
        scope <- owners
      yield
        val _ = round
        finished(tm, scope, chars = 20_000)
      val stats = runUnsafe(tm.stats)
      stats.perPool(PoolKind.Bearer).bytes should be <= settings.maxBytesTotal
      stats.perOwner.values.foreach(_.bytes should be <= settings.maxBytesPerOwner)
      val (kept, bytes) = retainedResultBytes(tm, ids, None)
      bytes should be <= settings.maxBytesTotal
      kept should be >= 1
      // The other pool has its own budget.
      val legacy = finished(tm, TaskScope.session("s"), chars = 20_000)
      runUnsafe(tm.snapshot(legacy, Some("s"))).flatMap(_.outcome).exists(_.isSuccess) shouldBe true
    finally runUnsafe(tm.shutdown)
  }

  test("a large failure is bounded like a large result") {
    val settings = TaskSettings(enabled = true, maxResultBytes = 16 * KiB)
    val tm = TaskManager.makeUnsafe[Any](settings, ZIO.succeed(java.util.UUID.randomUUID().toString))
    try
      val scope = TaskScope.bearer(Some("A"))
      val created = runUnsafe(
        tm.create(scope, None, ZIO.fail(new RuntimeException("e" * 100_000)), _ => ZIO.unit)
      )
      val stats = awaitStats(tm)(_.running == 0)
      stats.perOwner(scope.ownerKey).bytes shouldBe 0L
      val task = runUnsafe(tm.get(created.task.taskId, None)).getOrElse(fail("entry missing"))
      task.status.toString shouldBe "Failed"
      task.statusMessage.getOrElse("").length should be <= 1100
    finally runUnsafe(tm.shutdown)
  }

  test("the byte settings are validated at construction") {
    an[IllegalArgumentException] should be thrownBy TaskSettings(maxRequestBytes = 0)
    an[IllegalArgumentException] should be thrownBy TaskSettings(maxResultBytes = 0)
    an[IllegalArgumentException] should be thrownBy
      TaskSettings(maxResultBytes = 8 * KiB, maxRequestBytes = 8 * KiB, maxBytesPerOwner = 4 * KiB)
    an[IllegalArgumentException] should be thrownBy
      TaskSettings(maxBytesPerOwner = 64 * 1024 * KiB, maxBytesTotal = 32 * 1024 * KiB)
  }

  // ---------------------------------------------------------------------------------------------
  // (e) A task on a session with no reader for the out-of-request channel buffers nothing.
  // ---------------------------------------------------------------------------------------------

  test("a modern task's progress on a request-scoped session is not buffered anywhere") {
    val gate = runUnsafe(Promise.make[Nothing, Unit])
    val (router, tm) = build(TaskSettings(enabled = true), gate, chatter = 200)
    try
      val session = runUnsafe(Session.makeRequestScoped("r", clientKey = Some("A")))
      val taskId = taskIdOf(call(router, session, 1, modernParams("chatty")))
      val _ = awaitStats(tm)(s => s.total == 1 && s.running == 0)
      runUnsafe(tm.get(taskId, None)).map(_.status.toString) shouldBe Some("Completed")
      runUnsafe(session.outbound.size) shouldBe 0
    finally runUnsafe(tm.shutdown)
  }
