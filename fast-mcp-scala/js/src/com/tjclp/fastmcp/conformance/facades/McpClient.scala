package com.tjclp.fastmcp.conformance.facades

import scala.scalajs.js
import scala.scalajs.js.annotation.*

// --- Transport ---

@js.native
trait Transport extends js.Object

@JSImport("@modelcontextprotocol/sdk/client/stdio.js", "StdioClientTransport")
@js.native
class StdioClientTransport(params: StdioClientParams) extends Transport

trait StdioClientParams extends js.Object:
  val command: String
  val args: js.Array[String]
  val cwd: js.UndefOr[String]
  val stderr: js.UndefOr[String]

object StdioClientParams:

  def apply(
      command: String,
      args: js.Array[String],
      cwd: js.UndefOr[String] = js.undefined,
      stderr: js.UndefOr[String] = js.undefined
  ): StdioClientParams =
    js.Dynamic
      .literal(command = command, args = args, cwd = cwd, stderr = stderr)
      .asInstanceOf[StdioClientParams]

// --- Client info ---

trait ClientInfo extends js.Object:
  val name: String
  val version: String

object ClientInfo:

  def apply(name: String, version: String): ClientInfo =
    js.Dynamic.literal(name = name, version = version).asInstanceOf[ClientInfo]

trait ClientOptions extends js.Object:
  val capabilities: js.UndefOr[js.Object]

object ClientOptions:

  def apply(capabilities: js.Object = js.Dynamic.literal().asInstanceOf[js.Object]): ClientOptions =
    js.Dynamic.literal(capabilities = capabilities).asInstanceOf[ClientOptions]

// --- Client class ---

@JSImport("@modelcontextprotocol/sdk/client/index.js", "Client")
@js.native
class Client(info: ClientInfo, options: ClientOptions) extends js.Object:
  def connect(transport: Transport): js.Promise[Unit] = js.native
  def close(): js.Promise[Unit] = js.native

  def listTools(): js.Promise[ListToolsResult] = js.native
  def callTool(params: CallToolParams): js.Promise[CallToolResult] = js.native
  def listResources(): js.Promise[ListResourcesResult] = js.native
  def readResource(params: ReadResourceParams): js.Promise[ReadResourceResult] = js.native
  def listResourceTemplates(): js.Promise[ListResourceTemplatesResult] = js.native
  def listPrompts(): js.Promise[ListPromptsResult] = js.native
  def getPrompt(params: GetPromptParams): js.Promise[GetPromptResult] = js.native

  def getServerVersion(): js.UndefOr[ServerInfo] = js.native
  def getServerCapabilities(): js.UndefOr[ServerCapabilities] = js.native

// --- Server info / capabilities ---

@js.native
trait ServerInfo extends js.Object:
  val name: String = js.native
  val version: String = js.native

@js.native
trait ServerCapabilities extends js.Object:
  val tools: js.UndefOr[js.Object] = js.native
  val resources: js.UndefOr[js.Object] = js.native
  val prompts: js.UndefOr[js.Object] = js.native

// --- Tool types ---

@js.native
trait ToolDef extends js.Object:
  val name: String = js.native
  val description: js.UndefOr[String] = js.native
  val inputSchema: js.UndefOr[InputSchema] = js.native

@js.native
trait InputSchema extends js.Object:
  val `type`: String = js.native
  val properties: js.UndefOr[js.Dictionary[js.Object]] = js.native
  val required: js.UndefOr[js.Array[String]] = js.native

@js.native
trait ListToolsResult extends js.Object:
  val tools: js.Array[ToolDef] = js.native

trait CallToolParams extends js.Object:
  val name: String
  val arguments: js.UndefOr[js.Dictionary[js.Any]]

object CallToolParams:

  def apply(name: String, arguments: js.Dictionary[js.Any]): CallToolParams =
    js.Dynamic.literal(name = name, arguments = arguments).asInstanceOf[CallToolParams]

@js.native
trait CallToolResult extends js.Object:
  val content: js.Array[ContentBlock] = js.native
  val isError: js.UndefOr[Boolean] = js.native

@js.native
trait ContentBlock extends js.Object:
  val `type`: String = js.native
  val text: js.UndefOr[String] = js.native

// --- Resource types ---

@js.native
trait ResourceDef extends js.Object:
  val uri: String = js.native
  val name: js.UndefOr[String] = js.native
  val description: js.UndefOr[String] = js.native
  val mimeType: js.UndefOr[String] = js.native

@js.native
trait ResourceTemplateDef extends js.Object:
  val uriTemplate: String = js.native
  val name: js.UndefOr[String] = js.native
  val description: js.UndefOr[String] = js.native

@js.native
trait ListResourcesResult extends js.Object:
  val resources: js.Array[ResourceDef] = js.native

@js.native
trait ListResourceTemplatesResult extends js.Object:
  val resourceTemplates: js.Array[ResourceTemplateDef] = js.native

trait ReadResourceParams extends js.Object:
  val uri: String

object ReadResourceParams:

  def apply(uri: String): ReadResourceParams =
    js.Dynamic.literal(uri = uri).asInstanceOf[ReadResourceParams]

@js.native
trait ResourceContent extends js.Object:
  val uri: String = js.native
  val text: js.UndefOr[String] = js.native
  val blob: js.UndefOr[String] = js.native
  val mimeType: js.UndefOr[String] = js.native

@js.native
trait ReadResourceResult extends js.Object:
  val contents: js.Array[ResourceContent] = js.native

// --- Prompt types ---

@js.native
trait PromptDef extends js.Object:
  val name: String = js.native
  val description: js.UndefOr[String] = js.native
  val arguments: js.UndefOr[js.Array[PromptArgDef]] = js.native

@js.native
trait PromptArgDef extends js.Object:
  val name: String = js.native
  val description: js.UndefOr[String] = js.native
  val required: js.UndefOr[Boolean] = js.native

@js.native
trait ListPromptsResult extends js.Object:
  val prompts: js.Array[PromptDef] = js.native

trait GetPromptParams extends js.Object:
  val name: String
  val arguments: js.UndefOr[js.Dictionary[String]]

object GetPromptParams:

  def apply(name: String, arguments: js.Dictionary[String] = js.Dictionary.empty): GetPromptParams =
    js.Dynamic.literal(name = name, arguments = arguments).asInstanceOf[GetPromptParams]

@js.native
trait PromptMessageDef extends js.Object:
  val role: String = js.native
  val content: ContentBlock = js.native

@js.native
trait GetPromptResult extends js.Object:
  val messages: js.Array[PromptMessageDef] = js.native
