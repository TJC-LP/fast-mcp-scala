package com.tjclp.fastmcp
package server.transport

import scala.jdk.CollectionConverters.*

import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import zio.json.*
import zio.json.ast.Json
import zio.{System as _, Task as _, *}

import core.*
import core.TypeConversions.toJava
import server.McpContext
import server.manager.{TaskConcurrencyLimitExceeded, TaskManager, ToolManager}

/** Translates tasks-related JSON-RPC traffic into [[TaskManager]] operations.
  *
  * Lives between the HTTP transport and the Java SDK: the transport calls [[intercept]] for every
  * inbound JSON-RPC request and the dispatcher claims tasks methods (`tasks/get`, `tasks/list`,
  * `tasks/cancel`, `tasks/result`) plus task-augmented `tools/call` invocations. Anything else
  * falls through to the SDK.
  *
  * Outgoing JSON responses are post-processed via [[transformOutgoingJson]] so we can inject
  *   - `capabilities.tasks` on the `initialize` response, and
  *   - `execution.taskSupport` on each `tools/list` entry.
  *
  * Both fields are absent from the Java SDK's data model (it doesn't know about tasks), so the
  * post-process is the cheapest way to surface them on the wire without forking the SDK.
  */
@SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.AsInstanceOf"))
private[fastmcp] class TaskDispatcher(
    taskManager: TaskManager,
    toolManager: ToolManager,
    jsonMapper: McpJsonMapper
):

  /** Try to handle a JSON-RPC request entirely within the dispatcher. Returns `Some(effect)` when
    * the dispatcher claims the request; `None` to fall through to the SDK.
    */
  def intercept(
      request: McpSchema.JSONRPCRequest,
      sessionId: Option[String],
      ctx: Option[McpContext]
  ): Option[UIO[McpSchema.JSONRPCResponse]] =
    request.method() match
      case Tasks.MethodTasksGet =>
        Some(handleTasksGet(request, sessionId))
      case Tasks.MethodTasksList =>
        Some(handleTasksList(request, sessionId))
      case Tasks.MethodTasksCancel =>
        Some(handleTasksCancel(request, sessionId))
      case Tasks.MethodTasksResult =>
        Some(handleTasksResult(request, sessionId))
      case McpSchema.METHOD_TOOLS_CALL =>
        toolsCallInterception(request, sessionId, ctx)
      case _ =>
        None

  /** Heuristic post-processor for the outbound JSON-RPC frame. Idempotent: if the JSON has no
    * relevant fields (i.e. it's not an initialize or tools/list response) we return it unchanged.
    *
    * We rely on field shape rather than tracking pending request IDs because JSON-RPC responses
    * don't carry the original method name. A tools/list response is recognized by `result.tools`
    * being an array; an initialize response is recognized by `result.capabilities` being present.
    */
  def transformOutgoingJson(json: String): String =
    json.fromJson[Json] match
      case Right(Json.Obj(topFields))
          if topFields.exists(_._1 == "result") &&
            topFields.exists(_._1 == "jsonrpc") =>
        val mutated = topFields.map {
          case ("result", v) => "result" -> transformResult(v)
          case other => other
        }
        Json.Obj(mutated).toJson
      case _ => json

  /** Build a `notifications/tasks/status` notification payload for a status change. The HTTP
    * provider routes this through `notifyClients` to fan out over each session's SSE stream.
    */
  def buildStatusNotificationParams(task: Task): Object =
    taskToMap(task)

  // ---- request handlers ----

  private def handleTasksGet(
      request: McpSchema.JSONRPCRequest,
      sessionId: Option[String]
  ): UIO[McpSchema.JSONRPCResponse] =
    extractTaskId(request) match
      case Left(err) => ZIO.succeed(invalidParams(request, err))
      case Right(taskId) =>
        taskManager.get(taskId, sessionId).map {
          case None => invalidParams(request, s"Task not found: $taskId")
          case Some(task) => okResult(request, taskToMap(task))
        }

  private def handleTasksList(
      request: McpSchema.JSONRPCRequest,
      sessionId: Option[String]
  ): UIO[McpSchema.JSONRPCResponse] =
    val cursor = paramString(request, "cursor")
    taskManager.list(sessionId, cursor).map { result =>
      val resultMap = new java.util.LinkedHashMap[String, Object]()
      resultMap.put("tasks", result.tasks.map(taskToMap).asJava)
      result.nextCursor.foreach(c => resultMap.put("nextCursor", c))
      okResult(request, resultMap)
    }

  private def handleTasksCancel(
      request: McpSchema.JSONRPCRequest,
      sessionId: Option[String]
  ): UIO[McpSchema.JSONRPCResponse] =
    extractTaskId(request) match
      case Left(err) => ZIO.succeed(invalidParams(request, err))
      case Right(taskId) =>
        taskManager.cancel(taskId, sessionId).map {
          case Left(reason) => invalidParams(request, reason)
          case Right(task) => okResult(request, taskToMap(task))
        }

  private def handleTasksResult(
      request: McpSchema.JSONRPCRequest,
      sessionId: Option[String]
  ): UIO[McpSchema.JSONRPCResponse] =
    extractTaskId(request) match
      case Left(err) => ZIO.succeed(invalidParams(request, err))
      case Right(taskId) =>
        // Block until the task reaches a terminal status, then surface the underlying result.
        taskManager
          .result(taskId, sessionId)
          .fold(
            err =>
              new McpSchema.JSONRPCResponse(
                McpSchema.JSONRPC_VERSION,
                request.id(),
                null,
                new McpSchema.JSONRPCResponse.JSONRPCError(
                  McpSchema.ErrorCodes.INTERNAL_ERROR,
                  s"Task execution error: ${err.getMessage}",
                  null
                )
              ),
            value =>
              // The underlying value is whatever the original request produced (typically a
              // McpSchema.CallToolResult). The SDK's mapper handles either case.
              new McpSchema.JSONRPCResponse(
                McpSchema.JSONRPC_VERSION,
                request.id(),
                value,
                null
              )
          )

  /** When `tools/call` is task-augmented (i.e., `params.task` is present), wrap the tool execution
    * in a TaskManager task and return the synthesized [[CreateTaskResult]] immediately.
    *
    * Also enforces the per-tool `taskSupport` setting:
    *   - `Forbidden` (default) + `params.task`: returns `-32601`.
    *   - `Required` without `params.task`: returns `-32601`.
    *   - `Optional` + `params.task`: wraps with TaskManager.
    *   - Anything else: passes through to the SDK.
    */
  private def toolsCallInterception(
      request: McpSchema.JSONRPCRequest,
      sessionId: Option[String],
      ctx: Option[McpContext]
  ): Option[UIO[McpSchema.JSONRPCResponse]] =
    val paramsObj = paramsAsMap(request)
    val toolName = Option(paramsObj.get("name")).map(_.toString)
    val taskParamsPresent = paramsObj.containsKey("task")

    toolName match
      case None => None // malformed; let the SDK reply with its own error
      case Some(name) =>
        val taskSupport = toolManager.getToolDefinition(name).map(_.effectiveTaskSupport)
        (taskSupport, taskParamsPresent) match
          case (Some(TaskSupport.Required), false) =>
            Some(ZIO.succeed(methodNotFound(request, s"Tool '$name' requires task augmentation")))
          case (Some(TaskSupport.Forbidden) | None, true) =>
            Some(
              ZIO.succeed(
                methodNotFound(request, s"Tool '$name' does not support task augmentation")
              )
            )
          case (Some(TaskSupport.Optional) | Some(TaskSupport.Required), true) =>
            Some(executeAsTask(request, name, paramsObj, sessionId, ctx))
          case _ => None // taskSupport=Optional/Forbidden without params.task: normal call

  private def executeAsTask(
      request: McpSchema.JSONRPCRequest,
      toolName: String,
      paramsObj: java.util.Map[String, Object],
      sessionId: Option[String],
      ctx: Option[McpContext]
  ): UIO[McpSchema.JSONRPCResponse] =
    val argumentsRaw = Option(paramsObj.get("arguments")).getOrElse(java.util.Map.of())
    val argsMap: Map[String, Any] = argumentsRaw match
      case m: java.util.Map[?, ?] =>
        m.asScala.collect { case (k: String, v) => k -> (v: Any) }.toMap
      case _ => Map.empty[String, Any]

    val taskParamsRaw = Option(paramsObj.get("task"))
    val ttlMs = taskParamsRaw.flatMap {
      case m: java.util.Map[?, ?] =>
        m.asScala.collectFirst { case ("ttl", v: Number) => v.longValue() }
      case _ => None
    }

    toolManager.getToolHandler(toolName) match
      case None =>
        ZIO.succeed(invalidParams(request, s"Tool '$toolName' not found"))
      case Some(handler) =>
        // Erase the result type — TaskManager doesn't know what the tool returns, only the
        // dispatch layer does. The outgoing tasks/result will see the raw value passed back.
        val run: ZIO[Any, Throwable, Any] = handler(argsMap, ctx).flatMap { v =>
          ZIO.attempt(toCallToolResult(v))
        }
        taskManager
          .create(
            sessionId = sessionId,
            requestedTtlMs = ttlMs,
            run = run,
            onStatusChange = _ => ZIO.unit // notifications wired in the transport, not here
          )
          .fold(
            {
              // Cap exceeded is a request-rejection (-32602), not a generic server error.
              case _: TaskConcurrencyLimitExceeded =>
                new McpSchema.JSONRPCResponse(
                  McpSchema.JSONRPC_VERSION,
                  request.id(),
                  null,
                  new McpSchema.JSONRPCResponse.JSONRPCError(
                    McpSchema.ErrorCodes.INVALID_PARAMS,
                    s"Task concurrency limit exceeded for this session",
                    null
                  )
                )
              case err =>
                new McpSchema.JSONRPCResponse(
                  McpSchema.JSONRPC_VERSION,
                  request.id(),
                  null,
                  new McpSchema.JSONRPCResponse.JSONRPCError(
                    McpSchema.ErrorCodes.INTERNAL_ERROR,
                    err.getMessage,
                    null
                  )
                )
            },
            createResult => okResult(request, createTaskResultToMap(createResult))
          )

  // ---- JSON helpers ----

  private def transformResult(result: Json): Json = result match
    case Json.Obj(resultFields) =>
      val asMap = resultFields.toMap
      if asMap.contains("tools") then
        Json.Obj(resultFields.map {
          case (k, Json.Arr(items)) if k == "tools" =>
            k -> Json.Arr(items.map(injectExecution))
          case other => other
        })
      else if asMap.contains("capabilities") then
        Json.Obj(resultFields.map {
          case (k, v) if k == "capabilities" => k -> injectTasksCapability(v)
          case other => other
        })
      else result
    case _ => result

  private def injectExecution(toolJson: Json): Json = toolJson match
    case Json.Obj(fields) if !fields.exists(_._1 == "execution") =>
      val name = fields.toMap.get("name").flatMap {
        case Json.Str(s) => Some(s)
        case _ => None
      }
      name.flatMap(toolManager.getToolDefinition).flatMap(_.taskSupport) match
        case Some(ts) =>
          val executionJson = Json.Obj(
            "taskSupport" -> Json.Str(taskSupportWire(ts))
          )
          Json.Obj(fields :+ ("execution" -> executionJson))
        case None => toolJson
    case _ => toolJson

  private def injectTasksCapability(capabilities: Json): Json = capabilities match
    case Json.Obj(fields) if !fields.exists(_._1 == "tasks") =>
      val tasksJson = Json.Obj(
        "list" -> Json.Obj(),
        "cancel" -> Json.Obj(),
        "requests" -> Json.Obj(
          "tools" -> Json.Obj(
            "call" -> Json.Obj()
          )
        )
      )
      Json.Obj(fields :+ ("tasks" -> tasksJson))
    case _ => capabilities

  private def taskSupportWire(ts: TaskSupport): String = ts match
    case TaskSupport.Forbidden => "forbidden"
    case TaskSupport.Optional => "optional"
    case TaskSupport.Required => "required"

  private def taskToMap(task: Task): java.util.Map[String, Object] =
    val m = new java.util.LinkedHashMap[String, Object]()
    m.put("taskId", task.taskId)
    m.put("status", taskStatusWire(task.status))
    task.statusMessage.foreach(s => m.put("statusMessage", s))
    m.put("createdAt", task.createdAt)
    m.put("lastUpdatedAt", task.lastUpdatedAt)
    task.ttl.foreach(t => m.put("ttl", java.lang.Long.valueOf(t)))
    task.pollInterval.foreach(p => m.put("pollInterval", java.lang.Long.valueOf(p)))
    m

  private def createTaskResultToMap(result: CreateTaskResult): java.util.Map[String, Object] =
    val m = new java.util.LinkedHashMap[String, Object]()
    m.put("task", taskToMap(result.task))
    m

  private def taskStatusWire(s: TaskStatus): String = s match
    case TaskStatus.Working => "working"
    case TaskStatus.InputRequired => "input_required"
    case TaskStatus.Completed => "completed"
    case TaskStatus.Failed => "failed"
    case TaskStatus.Cancelled => "cancelled"

  private def paramsAsMap(request: McpSchema.JSONRPCRequest): java.util.Map[String, Object] =
    request.params() match
      case null => java.util.Map.of()
      case m: java.util.Map[?, ?] =>
        m.asInstanceOf[java.util.Map[String, Object]]
      case other =>
        // Convert via the SDK mapper (handles cases where params is a typed object).
        jsonMapper.convertValue(
          other,
          new io.modelcontextprotocol.json.TypeRef[java.util.Map[String, Object]]() {}
        )

  private def extractTaskId(request: McpSchema.JSONRPCRequest): Either[String, String] =
    paramString(request, "taskId").toRight("Missing required parameter: taskId")

  private def paramString(request: McpSchema.JSONRPCRequest, key: String): Option[String] =
    Option(paramsAsMap(request).get(key)).flatMap {
      case s: String => Some(s)
      case _ => None
    }

  /** Best-effort coercion of a tool handler's `Any` return value to a `CallToolResult`. The
    * `ToolManager` already does this conversion for normal calls in [[FastMcpServer]]; we mimic the
    * same logic here so the task path is symmetric.
    */
  private def toCallToolResult(value: Any): McpSchema.CallToolResult =
    value match
      case r: McpSchema.CallToolResult => r
      case s: String =>
        new McpSchema.CallToolResult(
          java.util.List.of[McpSchema.Content](new McpSchema.TextContent(null, s)),
          java.lang.Boolean.FALSE,
          null,
          null
        )
      case bytes: Array[Byte] =>
        val base64Data = java.util.Base64.getEncoder.encodeToString(bytes)
        new McpSchema.CallToolResult(
          java.util.List.of[McpSchema.Content](
            new McpSchema.ImageContent(null, base64Data, "application/octet-stream")
          ),
          java.lang.Boolean.FALSE,
          null,
          null
        )
      case c: Content =>
        new McpSchema.CallToolResult(
          java.util.List.of[McpSchema.Content](c.toJava),
          java.lang.Boolean.FALSE,
          null,
          null
        )
      case lst: List[?] if lst.nonEmpty && lst.head.isInstanceOf[Content] =>
        new McpSchema.CallToolResult(
          lst.asInstanceOf[List[Content]].map(_.toJava).asJava,
          java.lang.Boolean.FALSE,
          null,
          null
        )
      case null =>
        new McpSchema.CallToolResult(
          java.util.List.of[McpSchema.Content](),
          java.lang.Boolean.FALSE,
          null,
          null
        )
      case other =>
        new McpSchema.CallToolResult(
          java.util.List.of[McpSchema.Content](new McpSchema.TextContent(null, other.toString)),
          java.lang.Boolean.FALSE,
          null,
          null
        )

  private def okResult(
      request: McpSchema.JSONRPCRequest,
      result: Object
  ): McpSchema.JSONRPCResponse =
    new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null)

  private def invalidParams(
      request: McpSchema.JSONRPCRequest,
      message: String
  ): McpSchema.JSONRPCResponse =
    new McpSchema.JSONRPCResponse(
      McpSchema.JSONRPC_VERSION,
      request.id(),
      null,
      new McpSchema.JSONRPCResponse.JSONRPCError(
        McpSchema.ErrorCodes.INVALID_PARAMS,
        message,
        null
      )
    )

  private def methodNotFound(
      request: McpSchema.JSONRPCRequest,
      message: String
  ): McpSchema.JSONRPCResponse =
    new McpSchema.JSONRPCResponse(
      McpSchema.JSONRPC_VERSION,
      request.id(),
      null,
      new McpSchema.JSONRPCResponse.JSONRPCError(
        McpSchema.ErrorCodes.METHOD_NOT_FOUND,
        message,
        null
      )
    )
