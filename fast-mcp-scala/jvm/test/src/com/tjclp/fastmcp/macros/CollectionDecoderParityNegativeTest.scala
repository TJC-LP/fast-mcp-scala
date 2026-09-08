package com.tjclp.fastmcp
package macros

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacroDxHarness.{assertSomeMessageContains, messages}
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

object DxVectorOfEnum:
  @Tool(name = Some("vector_color"))
  def vectorColor(@Param("p") p: Vector[Color]): Int = p.size

object DxVectorOfCaseClass:
  @Tool(name = Some("vector_item"))
  def vectorItem(@Param("p") p: Vector[DxItem]): Int = p.size

object DxVectorOfOptionEnum:
  @Tool(name = Some("vector_opt_color"))
  def vectorOptColor(@Param("p") p: Vector[Option[Color]]): Int = p.size

/** A `Seq` subtype that is neither `List`, `Vector` nor `Seq` itself: the decoder derived for
  * `Seq[Color]` is not a `JsonDecoder[IndexedSeq[Color]]` (invariant), so synthesis cannot succeed
  * — it must say so instead of crashing the compiler.
  */
object DxIndexedSeqOfEnum:
  @Tool(name = Some("iseq_color"))
  def iseqColor(@Param("p") p: IndexedSeq[Color]): Int = p.size

/** Custom schema, no decoder anywhere: schema derivation succeeds, decoder synthesis must abort. */
final class DxSchemaOnly(val raw: String)

object DxSchemaOnly:
  given McpSchema[DxSchemaOnly] = McpSchema.instance("""{"type":"string"}""")

object DxSchemaOnlyParam:
  @Tool(name = Some("schema_only"))
  def schemaOnly(@Param("p") p: DxSchemaOnly): String = p.raw

/** TJC-2331 (C2 rows `S_set_color_map_ru_dn`, `S_either_set_ru_dn`, `P011`, `B_vector_item`,
  * `P012`): the schema macro derives `Set[Color]` (JsonSchemaMacro.scala Set case -> `uniqueItems`
  * array of the string enum) but decoder synthesis aborts with "No McpDecoder or derivable
  * JsonDecoder found for type: scala.collection.immutable.Set[Color]" — while `List[Color]` derives
  * on both sides. `Vector[T]` of a derived `T` is worse: the `Seq[a]` arm builds a
  * `JsonDecoder[Seq[T]]` and the invariant cast to `JsonDecoder[Vector[T]]` crashes the macro with
  * an `ExprCastException` and a compiler stack trace.
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

  test("Vector[Color] derives schema and decoder like List[Color] (no macro crash)") {
    val errs: List[Error] = typeCheckErrors(scan("DxVectorOfEnum"))
    assert(errs.isEmpty, s"Vector[Color] parameter does not compile:\n${messages(errs)}")
  }

  test("Vector[DxItem] derives schema and decoder") {
    val errs: List[Error] = typeCheckErrors(scan("DxVectorOfCaseClass"))
    assert(errs.isEmpty, s"Vector[DxItem] parameter does not compile:\n${messages(errs)}")
  }

  test("Vector[Option[Color]] derives schema and decoder") {
    val errs: List[Error] = typeCheckErrors(scan("DxVectorOfOptionEnum"))
    assert(errs.isEmpty, s"Vector[Option[Color]] parameter does not compile:\n${messages(errs)}")
  }

  test("a Seq subtype the decoder cannot build is a diagnostic, not an ExprCastException") {
    val errs: List[Error] = typeCheckErrors(scan("DxIndexedSeqOfEnum"))
    assert(errs.nonEmpty, "IndexedSeq[Color] compiled (unexpected: no decoder arm covers it)")
    assert(
      !errs.exists(e =>
        e.message.contains("ExprCastException") ||
          e.message.contains("Exception occurred while executing macro expansion")
      ),
      s"macro crashed instead of reporting:\n${messages(errs)}"
    )
    assertSomeMessageContains(errs, "'p'", "IndexedSeq", "given JsonDecoder[")
  }

  test("when decoder synthesis aborts, the diagnostic names the parameter and states a remedy") {
    val fixtures: List[(String, List[Error])] = List(
      "DxSetOfEnum" -> typeCheckErrors(scan("DxSetOfEnum")),
      "DxMapOfSetOfEnum" -> typeCheckErrors(scan("DxMapOfSetOfEnum")),
      "DxSetOfEither" -> typeCheckErrors(scan("DxSetOfEither")),
      "DxSchemaOnlyParam" -> typeCheckErrors(scan("DxSchemaOnlyParam"))
    )
    val schemaOnly = typeCheckErrors(scan("DxSchemaOnlyParam"))
    assert(schemaOnly.nonEmpty, "DxSchemaOnly has no decoder anywhere yet the tool compiled")
    val unhelpful = fixtures.flatMap { case (fixture, errs) =>
      val helpful = errs.isEmpty || errs.exists { e =>
        e.message.contains("'p'") &&
        (e.message.contains("given") || e.message.contains("McpInputCodec"))
      }
      Option.when(!helpful)(s"$fixture:\n${messages(errs)}")
    }
    assert(unhelpful.isEmpty, unhelpful.mkString("\n===\n"))
  }
