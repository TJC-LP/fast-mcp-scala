package downstream

import scala.compiletime.testing.{typeCheckErrors, Error}

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.{*, given}

enum DiagParamEnum:
  case Dot
  case Circle(radius: Int)

case class DiagParamEnumArgs(shape: DiagParamEnum)

sealed trait DiagTree
case class DiagNode(children: List[DiagTree]) extends DiagTree
case object DiagEnd extends DiagTree

case class DiagTreeArgs(tree: DiagTree)

sealed trait DiagBox[T]
case class DiagFull[T](value: T) extends DiagBox[T]

case class DiagBoxArgs(box: DiagBox[Int])

sealed trait DiagRoot
case class DiagOnly(n: Int) extends DiagRoot

sealed trait DiagConfigured
case class DiagLeft(n: Int) extends DiagConfigured
case object DiagRight extends DiagConfigured

case class DiagConfiguredOut(value: DiagConfigured)
case class DiagIn(n: Int)

@zio.json.jsonHintNames(zio.json.CustomCase(_.toUpperCase)) sealed trait DiagCustomNames
case class DiagNamed(n: Int) extends DiagCustomNames

case class DiagCustomNamesArgs(value: DiagCustomNames)

object DiagNames:
  val notConstant: String = "computed".reverse

@zio.json.jsonDiscriminator(DiagNames.notConstant) sealed trait DiagComputed
case class DiagComputedCase(n: Int) extends DiagComputed

case class DiagComputedArgs(value: DiagComputed)

object DiagParamEnumTools:
  @Tool() def f(shape: DiagParamEnum): String = shape.toString

object DiagTreeTools:
  @Tool() def f(tree: DiagTree): String = tree.toString

object DiagBoxTools:
  @Tool() def f(box: DiagBox[Int]): String = box.toString

/** GH #119, row C2.24: the hierarchies that are still not derived fail when the server is compiled,
  * with a message that names the remedy.
  */
class AdtArgumentDiagnosticsTest extends AnyFunSuite:

  private def messages(errors: List[Error]): List[String] = errors.map(_.message)

  private def assertNamed(errors: List[Error], fragments: String*): Unit =
    fragments.foreach { fragment =>
      assert(
        messages(errors).exists(_.contains(fragment)),
        s"expected a compile error containing <$fragment>, got: ${messages(errors).mkString("\n---\n")}"
      )
    }

  test("an enum with a parameterized case is ONE error naming McpInputCodec, on both paths") {
    val typed = typeCheckErrors("""McpTool[DiagParamEnumArgs, String](name = "t")(_.toString)""")
    val annotated = typeCheckErrors("""McpServer("x").scanAnnotations[DiagParamEnumTools.type]""")
    List(typed, annotated).foreach { errors =>
      assert(errors.size == 1, messages(errors).mkString("\n---\n"))
      assertNamed(errors, "Provide a given McpInputCodec[downstream.DiagParamEnum]")
    }
  }

  test("a recursive hierarchy names the type and the codec to provide") {
    assertNamed(
      typeCheckErrors("""McpTool[DiagTreeArgs, String](name = "t")(_.toString)"""),
      "Recursive type downstream.DiagNode cannot be inlined into an MCP tool schema",
      "McpSchema[downstream.DiagNode]"
    )
    assertNamed(
      typeCheckErrors("""McpServer("x").scanAnnotations[DiagTreeTools.type]"""),
      "Recursive type downstream.DiagNode cannot be inlined into an MCP tool schema",
      "McpInputCodec[downstream.DiagNode]"
    )
  }

  test("a generic sealed trait names McpInputCodec") {
    val typed = typeCheckErrors("""McpTool[DiagBoxArgs, String](name = "t")(_.toString)""")
    val annotated = typeCheckErrors("""McpServer("x").scanAnnotations[DiagBoxTools.type]""")
    List(typed, annotated).foreach { errors =>
      assertNamed(errors, "a generic sealed trait's child schemas cannot be resolved", "McpInputCodec")
    }
  }

  test("a sealed trait as the In of a typed tool is refused: arguments is always an object") {
    val errors = typeCheckErrors("""McpTool[DiagRoot, String](name = "t")(_.toString)""")
    assert(errors.nonEmpty)
  }

  // ---- review of PR #158: a setting of the codec that cannot be read is refused ----

  test("outputSchema of a hierarchy under a given JsonCodecConfiguration is refused, naming the remedy") {
    val refused = typeCheckErrors(
      """
      given zio.json.JsonCodecConfiguration = zio.json.JsonCodecConfiguration(
        sumTypeHandling = zio.json.JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("kind")
      )
      McpTool[DiagIn, DiagConfiguredOut](name = "t")(in => DiagConfiguredOut(DiagLeft(in.n))).withOutputSchema
      """
    )
    assertNamed(
      refused,
      "Cannot derive an MCP JSON Schema for the sealed hierarchy downstream.DiagConfigured",
      "a given zio.json.JsonCodecConfiguration is in scope",
      "McpSchema[downstream.DiagConfigured]",
      "McpInputCodec[downstream.DiagConfigured]"
    )
    // Without the configuration, and without .withOutputSchema under it, the same tool compiles.
    assert(
      typeCheckErrors(
        """McpTool[DiagIn, DiagConfiguredOut](name = "t")(in => DiagConfiguredOut(DiagLeft(in.n))).withOutputSchema"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """
        given zio.json.JsonCodecConfiguration = zio.json.JsonCodecConfiguration(
          sumTypeHandling = zio.json.JsonCodecConfiguration.SumTypeHandling.DiscriminatorField("kind")
        )
        McpTool[DiagIn, DiagConfiguredOut](name = "t")(in => DiagConfiguredOut(DiagLeft(in.n)))
        """
      ).isEmpty
    )
  }

  test("@jsonHintNames with a CustomCase function is refused, naming @jsonHint and McpInputCodec") {
    assertNamed(
      typeCheckErrors("""McpTool[DiagCustomNamesArgs, String](name = "t")(_.toString)"""),
      "the format of @jsonHintNames",
      "Name the cases with @jsonHint",
      "McpInputCodec[downstream.DiagCustomNames]"
    )
  }

  test("an annotation argument that is not a constant is refused; a final val is not") {
    assertNamed(
      typeCheckErrors("""McpTool[DiagComputedArgs, String](name = "t")(_.toString)"""),
      "the argument of @jsonDiscriminator is not a compile-time constant",
      "`final val` constant",
      "McpInputCodec"
    )
  }
