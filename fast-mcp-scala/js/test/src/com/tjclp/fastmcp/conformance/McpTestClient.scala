package com.tjclp.fastmcp.conformance

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSGlobal

import com.tjclp.fastmcp.conformance.facades.*

@js.native
@JSGlobal("process")
private object NodeProcess extends js.Object:
  val env: js.Dictionary[String] = js.native

/** High-level Scala wrapper around the MCP TS SDK client for conformance testing. */
@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
class McpTestClient private (val underlying: Client):

  def listTools(): Future[ListToolsResult] =
    McpTestClient.fromJsPromise(underlying.listTools())

  def callTool(name: String, arguments: Map[String, Any]): Future[CallToolResult] =
    val jsArgs = js.Dictionary(arguments.view.mapValues(_.asInstanceOf[js.Any]).toSeq*)
    McpTestClient.fromJsPromise(underlying.callTool(CallToolParams(name, jsArgs)))

  def listResources(): Future[ListResourcesResult] =
    McpTestClient.fromJsPromise(underlying.listResources())

  def readResource(uri: String): Future[ReadResourceResult] =
    McpTestClient.fromJsPromise(underlying.readResource(ReadResourceParams(uri)))

  def listResourceTemplates(): Future[ListResourceTemplatesResult] =
    McpTestClient.fromJsPromise(underlying.listResourceTemplates())

  def listPrompts(): Future[ListPromptsResult] =
    McpTestClient.fromJsPromise(underlying.listPrompts())

  def getPrompt(name: String, arguments: Map[String, String] = Map.empty): Future[GetPromptResult] =
    val jsArgs = js.Dictionary(arguments.toSeq*)
    McpTestClient.fromJsPromise(underlying.getPrompt(GetPromptParams(name, jsArgs)))

  def serverName: Option[String] =
    underlying.getServerVersion().toOption.map(_.name)

  def serverVersion: Option[String] =
    underlying.getServerVersion().toOption.map(_.version)

  def hasToolCapability: Boolean =
    underlying.getServerCapabilities().toOption.exists(_.tools.isDefined)

  def hasResourceCapability: Boolean =
    underlying.getServerCapabilities().toOption.exists(_.resources.isDefined)

  def hasPromptCapability: Boolean =
    underlying.getServerCapabilities().toOption.exists(_.prompts.isDefined)

  def close(): Future[Unit] =
    McpTestClient.fromJsPromise(underlying.close())

@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
object McpTestClient:

  private def env(name: String): Option[String] =
    NodeProcess.env.get(name).filter(_.nonEmpty)

  private def fromJsPromise[A](promise: js.Promise[A]): Future[A] =
    val scalaPromise = Promise[A]()

    val _ = promise.`then`[Unit](
      (value: A) => {
        val _ = scalaPromise.trySuccess(value)
        ()
      },
      (error: scala.Any) => {
        val _ = scalaPromise.tryFailure(js.JavaScriptException(error))
        ()
      }
    )

    scalaPromise.future

  private def parseJsonStringArray(value: String): Seq[String] =
    js.JSON.parse(value).asInstanceOf[js.Array[String]].toSeq

  private def serverTransportParams(
      serverClass: String,
      projectRoot: String
  ): StdioClientParams =
    (env("FAST_MCP_SERVER_JAVA_CMD"), env("FAST_MCP_SERVER_CLASSPATH")) match
      case (Some(javaCmd), Some(classpath)) =>
        val jvmArgs =
          env("FAST_MCP_SERVER_JVM_ARGS_JSON").map(parseJsonStringArray).getOrElse(Seq.empty)
        // Launch the JVM server directly so bunTest does not deadlock on a nested Mill invocation.
        StdioClientParams(
          command = javaCmd,
          args = js.Array((jvmArgs ++ Seq("-cp", classpath, serverClass))*),
          cwd = projectRoot,
          stderr = "inherit"
        )
      case _ =>
        StdioClientParams(
          command = s"$projectRoot/mill",
          args = js.Array("--no-server", "fast-mcp-scala.jvm.runMain", serverClass),
          cwd = projectRoot,
          stderr = "inherit"
        )

  /** Connect to a fast-mcp-scala server via stdio transport. */
  def connectStdio(
      serverClass: String,
      projectRoot: String = NodeProcess.env.getOrElse("FAST_MCP_PROJECT_ROOT", ".")
  )(using ec: ExecutionContext): Future[McpTestClient] =
    val transport = new StdioClientTransport(
      serverTransportParams(serverClass, projectRoot)
    )
    val client = new Client(
      ClientInfo("fast-mcp-conformance", "1.0.0"),
      ClientOptions()
    )
    fromJsPromise(client.connect(transport)).map(_ => new McpTestClient(client))
