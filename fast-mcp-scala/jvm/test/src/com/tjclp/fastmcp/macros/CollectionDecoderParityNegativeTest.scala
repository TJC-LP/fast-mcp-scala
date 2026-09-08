package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacroDxHarness.messages
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

// ---------------------------------------------------------------------------------------------
// Collections of macro-derived element types (TOP-LEVEL so typeCheckErrors can name them).
// ---------------------------------------------------------------------------------------------

object DxSetOfEnum:
  @Tool(name = Some("set_color"))
  def setColor(@Param("p") p: Set[Color]): Int = p.size

object DxMapOfSetOfEnum:
  @Tool(name = Some("map_set_color"))
  def mapSetColor(@Param("p") p: Map[String, Set[Color]]): Int = p.size

object DxSetOfEither:
  @Tool(name = Some("set_either"))
  def setEither(@Param("p") p: Set[Either[Color, String]]): Int = p.size

object DxListOfEnum:
  @Tool(name = Some("list_color"))
  def listColor(@Param("p") p: List[Color]): Int = p.size

/** TJC-2331 (C2 rows `S_set_color_map_ru_dn`, `S_either_set_ru_dn`, `P011`): the schema macro
  * derives `Set[Color]` (JsonSchemaMacro.scala Set case -> `uniqueItems` array of the string enum)
  * but decoder synthesis aborts with "No McpDecoder or derivable JsonDecoder found for type:
  * scala.collection.immutable.Set[Color]" — while `List[Color]` derives on both sides.
  * docs/custom-types.md § "What derives automatically" promises enums "through Option, collections,
  * and nested case classes" with no per-type givens, so `Set` must derive like `List`. The abort is
  * also positioned on the object header and names neither the parameter nor a remedy.
  */
class CollectionDecoderParityNegativeTest extends AnyFunSuite:

  private inline def scan(inline obj: String): String =
    "val s = McpServer.typed[Any](\"neg\"); s.scanAnnotations[" + obj + ".type]"

  test("control: List[Color] derives schema and decoder") {
    val errs: List[Error] = typeCheckErrors(scan("DxListOfEnum"))
    assert(errs.isEmpty, messages(errs))
  }

  test("Set[Color] derives schema and decoder like List[Color]") {
    val errs: List[Error] = typeCheckErrors(scan("DxSetOfEnum"))
    assert(errs.isEmpty, s"Set[Color] parameter does not compile:\n${messages(errs)}")
  }

  test("Map[String, Set[Color]] derives schema and decoder") {
    val errs: List[Error] = typeCheckErrors(scan("DxMapOfSetOfEnum"))
    assert(errs.isEmpty, s"Map[String, Set[Color]] parameter does not compile:\n${messages(errs)}")
  }

  test("Set[Either[Color, String]] derives schema and decoder") {
    val errs: List[Error] = typeCheckErrors(scan("DxSetOfEither"))
    assert(
      errs.isEmpty,
      s"Set[Either[Color, String]] parameter does not compile:\n${messages(errs)}"
    )
  }

  test("when decoder synthesis aborts, the diagnostic names the parameter and states a remedy") {
    val fixtures: List[(String, List[Error])] = List(
      "DxSetOfEnum" -> typeCheckErrors(scan("DxSetOfEnum")),
      "DxMapOfSetOfEnum" -> typeCheckErrors(scan("DxMapOfSetOfEnum")),
      "DxSetOfEither" -> typeCheckErrors(scan("DxSetOfEither"))
    )
    val unhelpful = fixtures.flatMap { case (fixture, errs) =>
      val helpful = errs.isEmpty || errs.exists { e =>
        e.message.contains("'p'") &&
        (e.message.contains("given") || e.message.contains("McpInputCodec"))
      }
      Option.when(!helpful)(s"$fixture:\n${messages(errs)}")
    }
    assert(unhelpful.isEmpty, unhelpful.mkString("\n===\n"))
  }
