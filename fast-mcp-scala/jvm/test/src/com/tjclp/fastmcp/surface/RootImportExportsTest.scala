package com.tjclp.fastmcp.surface

import scala.compiletime.testing.typeCheckErrors

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{given, *}

// Top-level so the typeCheckErrors snippets can name it (a block-local case class would be typed
// inside the quoted snippet, where schema derivation has no stable owner to summon from).
case class ExpensiveArgs(@Param("input") x: String)

/** The root import (`import com.tjclp.fastmcp.{*, given}`) must be enough for every documented
  * fence (TJC-2336): the settings sub-records (`TaskSettings`, `LimitSettings`), the per-tool task
  * policy (`TaskSupport`, `TaskOwnerKey`) and the `resources/read` payload ADT
  * (`ResourceContents`, `TextResourceContents`, `BlobResourceContents`).
  *
  * Every snippet is type-checked in THIS file's scope — root import only, nothing from `server.*`,
  * `core.*` or `core.wire.*` — so a missing export is a failing test here instead of a compile error
  * the first reader of `docs/tasks.md` hits. The last test is the ambiguity probe: the root import
  * side by side with the `server.*` / `core.*` / `core.wire.*` wildcards must still resolve every
  * exported name (an export alias and its target are one reference, not two).
  */
class RootImportExportsTest extends AnyFunSuite:

  private def messages(errors: List[scala.compiletime.testing.Error]): List[String] =
    errors.map(_.message)

  private def assertCompiles(errors: List[scala.compiletime.testing.Error]): org.scalatest.Assertion =
    assert(errors == Nil, s"unexpected errors: ${messages(errors).mkString("\n---\n")}")

  test("docs/tasks.md: enabling tasks needs only the root import (TaskSettings)") {
    assertCompiles(typeCheckErrors("""
      val server = McpServer(
        name = "my-server",
        settings = McpServerSettings(tasks = TaskSettings(enabled = true))
      )
    """))
  }

  test("docs/tasks.md: the typed-contract opt-in needs only the root import (TaskSupport)") {
    assertCompiles(typeCheckErrors("""
      val tool = McpTool[ExpensiveArgs, String](name = "expensive-op")(args => args.x)
        .withTaskSupport(TaskSupport.Optional)
    """))
  }

  test("docs/tasks.md: the owner-key policy needs only the root import (TaskOwnerKey)") {
    assertCompiles(typeCheckErrors("""
      val byTransport = TaskSettings(enabled = true, ownerKey = TaskOwnerKey.Transport)
      val byPrincipal =
        TaskSettings(enabled = true, ownerKey = TaskOwnerKey.Custom(_.transportClientKey))
    """))
  }

  test("docs/transports.md: raising the frame limit needs only the root import (LimitSettings)") {
    assertCompiles(typeCheckErrors("""
      val settings = McpServerSettings(limits = LimitSettings(maxFrameChars = 8 * 1024 * 1024))
      val ceiling: Int = LimitSettings.MaxSupportedDepth
    """))
  }

  test("an EmbeddedResource payload needs only the root import (ResourceContents ADT)") {
    assertCompiles(typeCheckErrors("""
      val text: ResourceContents =
        TextResourceContents("file:///notes.txt", "hello", Some("text/plain"))
      val blob: ResourceContents =
        BlobResourceContents("file:///logo.png", "iVBORw0KGgo=", Some("image/png"))
      val contents: List[Content] = List(EmbeddedResource(text), EmbeddedResource(blob))
    """))
  }

  test("ambiguity probe: root import beside server.* / core.* / core.wire.* wildcards resolves") {
    assertCompiles(typeCheckErrors("""
      import com.tjclp.fastmcp.*
      import com.tjclp.fastmcp.server.*
      import com.tjclp.fastmcp.core.*
      import com.tjclp.fastmcp.core.wire.*

      val settings: McpServerSettings =
        McpServerSettings(tasks = TaskSettings(enabled = true), limits = LimitSettings())
      val policy: TaskSupport = TaskSupport.Optional
      val key: TaskOwnerKey = TaskOwnerKey.Transport
      val text: ResourceContents = TextResourceContents("file:///a.txt", "a")
      val blob: ResourceContents = BlobResourceContents("file:///a.bin", "AAAA")
    """))
  }
