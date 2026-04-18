package com.tjclp.fastmcp
package server

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*

/** Declarative entry point for building an MCP server.
  *
  * Extend this trait on a top-level `object` to mount annotated tools/prompts/resources and/or
  * typed contracts. The transport is a phantom type parameter — compile-time dispatch selects
  * `runStdio()` or `runHttp()` based on the matching [[TransportRunner]] given.
  *
  * {{{
  *   object HelloWorld extends McpServerApp[Stdio, HelloWorld.type]:
  *     @Tool(name = Some("add"))
  *     def add(@Param("a") a: Int, @Param("b") b: Int): Int = a + b
  * }}}
  *
  * Override `name`, `version`, `settings`, or the `tools` / `prompts` / `staticResources` /
  * `templateResources` lists to compose annotation scanning with typed contracts.
  *
  * @tparam T
  *   transport marker ([[Transport.Stdio]] or [[Transport.Http]])
  * @tparam Self
  *   singleton type of the enclosing object; the annotation-scan macro targets this type so
  *   `@Tool` / `@Prompt` / `@Resource` methods declared alongside get registered
  */
trait McpServerApp[T <: Transport, Self <: Singleton](using
    runner: TransportRunner[T],
    factory: McpServerCoreFactory
) extends zio.ZIOAppDefault:

  def name: String = getClass.getSimpleName.stripSuffix("$")
  def version: String = "0.1.0"
  def settings: McpServerSettings = McpServerSettings()

  def tools: List[McpTool[?, ?]] = Nil
  def prompts: List[McpPrompt[?]] = Nil
  def staticResources: List[McpStaticResource] = Nil
  def templateResources: List[McpTemplateResource[?]] = Nil

  /** Inlined so `Self` specializes at the subclass compilation site — the quiet variant suppresses
    * the "no annotations found" warning for contract-only servers that still declare Self.
    */
  protected inline final def scanSelf(core: McpServerCore): McpServerCore =
    core.scanAnnotationsQuiet[Self]

  final def buildCore: ZIO[Any, Throwable, McpServerCore] =
    val core = factory.build(name, version, settings)
    val _ = scanSelf(core)
    for
      _ <- ZIO.foreachDiscard(tools)(core.tool(_))
      _ <- ZIO.foreachDiscard(prompts)(core.prompt(_))
      _ <- ZIO.foreachDiscard(staticResources)(core.resource(_))
      _ <- ZIO.foreachDiscard(templateResources)(core.resource(_))
    yield core

  override final def run: ZIO[Any, Throwable, Unit] =
    buildCore.flatMap(runner.run)
