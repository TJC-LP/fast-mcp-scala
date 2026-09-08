package com.tjclp.fastmcp
package macros

import org.scalacheck.{Gen, Prop, Test}
import org.scalacheck.Prop.propBoolean
import org.scalacheck.rng.Seed
import org.scalacheck.util.Pretty
import org.scalatest.funsuite.AnyFunSuite
import zio.json.*
import zio.json.ast.Json

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.scanAnnotations

/** Parameter shapes whose advertised `inputSchema` and generated decoder must agree. The `ctl_*`
  * tools are controls: if their properties fail, the schema-driven generator is wrong, not the
  * macro.
  */
object WireShapeTools:

  @Tool(name = Some("ctl_int"))
  def ctlInt(@Param("p") p: Int): String = p.toString

  @Tool(name = Some("ctl_list_color"))
  def ctlListColor(@Param("p") p: List[Color]): String = p.toString

  @Tool(name = Some("ctl_item"))
  def ctlItem(@Param("p") p: DxItem): String = p.toString

  @Tool(name = Some("ctl_map_int"))
  def ctlMapInt(@Param("p") p: Map[String, Int]): String = p.toString

  // Either of a macro-derived (enum) payload: the schema says {"Left": "RED"} (JsonSchemaMacro
  // Either case) while the Mirror-derived fallback decoder wants {"Left": {"value": "RED"}}.
  @Tool(name = Some("either_plain"))
  def eitherPlain(@Param("p") p: Either[Color, String]): String = p.toString

  @Tool(name = Some("either_list"))
  def eitherList(@Param("p") p: List[Either[Color, String]]): String = p.toString

  @Tool(name = Some("either_map"))
  def eitherMap(@Param("p") p: Map[String, Either[Color, String]]): String = p.toString

  @Tool(name = Some("either_option"))
  def eitherOption(@Param("p", required = false) p: Option[Either[Color, String]]): String =
    p.toString

  // Tuples: productSchema advertises {"_1": ..., "_2": ...}. A bare tuple parameter decodes that
  // object shape (Mirror-derived product decoder), but once wrapped in Option the summoned
  // zio-json tuple decoder demands a JSON array — the same type, two wire shapes.
  @Tool(name = Some("tuple_plain"))
  def tuplePlain(@Param("p") p: (Int, String)): String = p.toString

  @Tool(name = Some("tuple_option"))
  def tupleOption(@Param("p", required = false) p: Option[(Int, String)]): String = p.toString

  // Java enum: schema is a bare {"type": "string"}; the decoder accepts only the seven names.
  @Tool(name = Some("dayofweek"))
  def dayOfWeek(@Param("p") p: java.time.DayOfWeek): String = p.toString

/** Generates JSON instances that conform to a schema emitted by [[JsonSchemaMacro]] (the subset it
  * produces: scalars, `enum`, `anyOf`/`oneOf`, arrays with `items`/`uniqueItems`, objects with
  * `properties`/`required` or `additionalProperties`).
  */
object SchemaGen:

  def conforming(schema: Json): Gen[Json] =
    val obj = schema.asObject.getOrElse(Json.Obj())
    def field(key: String): Option[Json] = obj.get(key)

    field("enum").flatMap(_.asArray).filter(_.nonEmpty) match
      case Some(values) => Gen.oneOf(values.toList)
      case None =>
        field("anyOf").orElse(field("oneOf")).flatMap(_.asArray).filter(_.nonEmpty) match
          case Some(alternatives) => Gen.oneOf(alternatives.toList).flatMap(conforming)
          case None =>
            field("type").flatMap(_.asString) match
              case Some("string") => Gen.alphaNumStr.map(Json.Str(_))
              case Some("integer") => Gen.chooseNum(-1000, 1000).map(Json.Num(_))
              case Some("number") => Gen.chooseNum(-1000.0, 1000.0).map(d => Json.Num(BigDecimal(d)))
              case Some("boolean") => Gen.oneOf(true, false).map(Json.Bool(_))
              case Some("null") => Gen.const(Json.Null)
              case Some("array") =>
                val items = field("items").getOrElse(Json.Obj())
                val unique = field("uniqueItems").flatMap(_.asBoolean).getOrElse(false)
                Gen
                  .choose(0, 4)
                  .flatMap(n => Gen.listOfN(n, conforming(items)))
                  .map(list => if unique then list.distinct else list)
                  .map(list => Json.Arr(list*))
              case Some("object") => objectFrom(obj, onlyRequired = false)
              case _ => Gen.const(Json.Obj())

  /** The instance carrying only the `required` keys of the root object (optional keys omitted). */
  def requiredOnly(schema: Json): Gen[Json] =
    objectFrom(schema.asObject.getOrElse(Json.Obj()), onlyRequired = true)

  private def objectFrom(obj: Json.Obj, onlyRequired: Boolean): Gen[Json] =
    obj.get("properties").flatMap(_.asObject) match
      case Some(properties) =>
        val required = MacroDxHarness.stringArray(obj, "required").toSet
        val entryGens: List[Gen[Option[(String, Json)]]] = properties.fields.toList.map {
          case (name, propertySchema) =>
            if required.contains(name) then conforming(propertySchema).map(v => Some(name -> v))
            else if onlyRequired then Gen.const(None)
            else Gen.option(conforming(propertySchema)).map(_.map(name -> _))
        }
        sequence(entryGens).map(entries => Json.Obj(entries.flatten*))
      case None =>
        obj.get("additionalProperties") match
          case Some(valueSchema) if valueSchema.asObject.isDefined =>
            Gen
              .choose(0, 3)
              .flatMap(n => Gen.listOfN(n, Gen.zip(Gen.identifier, conforming(valueSchema))))
              .map(entries => Json.Obj(entries.distinctBy(_._1)*))
          case _ => Gen.const(Json.Obj())

  private def sequence[A](gens: List[Gen[A]]): Gen[List[A]] =
    gens.foldRight(Gen.const(List.empty[A])) { (gen, acc) =>
      for
        head <- gen
        tail <- acc
      yield head :: tail
    }

/** TJC-2331 (C2 step 3; rows `S_either_*`, `P027 tuple`, `B_dayofweek`): for every tool, every
  * argument object that conforms to the advertised `inputSchema` must decode. Today the Either and
  * tuple schemas describe a wire shape the decoder rejects, and the Java-enum schema admits strings
  * the decoder refuses, so a schema-honouring client cannot call these tools at all.
  */
class SchemaDecoderConsistencyTest extends AnyFunSuite:

  private lazy val h = MacroDxHarness("wire-shape") { server =>
    val _ = server.scanAnnotations[WireShapeTools.type]
  }

  private val controls = List("ctl_int", "ctl_list_color", "ctl_item", "ctl_map_int")

  /** Live rows: `Either` of a derived payload in every wrapper (C2.3) plus the bare tuple. */
  private val suspects =
    List("either_plain", "either_list", "either_map", "either_option", "tuple_plain")

  private val params =
    Test.Parameters.default.withMinSuccessfulTests(40).withInitialSeed(Seed(20260908L))

  private def check(prop: Prop): Unit =
    val result = Test.check(params, prop)
    assert(result.passed, Pretty.pretty(result, Pretty.Params(2)))

  private def acceptsEveryConformingInstance(tool: String): Unit =
    val schema = h.inputSchema(tool)
    check(Prop.forAllNoShrink(SchemaGen.conforming(schema)) { args =>
      val out = h.call(tool, args)
      (!out.isError) :| s"tool=$tool args=${args.toJson} reply=${out.text}"
    })

  private def acceptsRequiredOnlyInstance(tool: String): Unit =
    val schema = h.inputSchema(tool)
    check(Prop.forAllNoShrink(SchemaGen.requiredOnly(schema)) { args =>
      val out = h.call(tool, args)
      (!out.isError) :| s"tool=$tool required-only args=${args.toJson} reply=${out.text}"
    })

  for tool <- controls do
    test(s"control $tool: every schema-conforming argument object decodes") {
      acceptsEveryConformingInstance(tool)
    }

  for tool <- suspects do
    test(s"$tool: every schema-conforming argument object decodes") {
      acceptsEveryConformingInstance(tool)
    }

    test(s"$tool: the required-keys-only argument object decodes") {
      acceptsRequiredOnlyInstance(tool)
    }

  // DEFERRED to 1.0.1 (C2.13): `Option[(Int, String)]` advertises the `{"_1","_2"}` object that the
  // bare tuple decodes, but the Option arm reaches zio-json's tuple codec, which wants a JSON array.
  test("tuple_option: every schema-conforming argument object decodes") {
    pendingUntilFixed(acceptsEveryConformingInstance("tuple_option"))
  }

  test("tuple_option: the required-keys-only argument object decodes") {
    acceptsRequiredOnlyInstance("tuple_option")
  }

  // DEFERRED to 1.0.1 (C2.14): the `java.time.DayOfWeek` schema is a bare string while the decoder
  // accepts only the seven names — the schema must carry `enum`.
  test("dayofweek: every schema-conforming argument object decodes") {
    pendingUntilFixed(acceptsEveryConformingInstance("dayofweek"))
  }

  test("dayofweek: the required-keys-only argument object decodes") {
    pendingUntilFixed(acceptsRequiredOnlyInstance("dayofweek"))
  }

  test("dropping a required key is rejected (non-Option parameters)") {
    val nonOption = (controls ++ suspects :+ "dayofweek").filterNot(Set("either_option"))
    nonOption.foreach { tool =>
      assert(h.required(tool) == List("p"), s"$tool required = ${h.required(tool)}")
      val out = h.call(tool, "{}")
      assert(out.isError, s"$tool accepted {} although p is required: $out")
    }
  }

  test("Either[Color, String]: the advertised {\"Left\": \"RED\"} shape decodes") {
    val left = h.call("either_plain", """{"p":{"Left":"RED"}}""")
    assert(!left.isError && left.text == "Left(RED)", s"Left reply: $left")
    val right = h.call("either_plain", """{"p":{"Right":"x"}}""")
    assert(!right.isError && right.text == "Right(x)", s"Right reply: $right")
  }

  test("(Int, String): the advertised {\"_1\": 1, \"_2\": \"x\"} shape decodes") {
    val out = h.call("tuple_plain", """{"p":{"_1":1,"_2":"x"}}""")
    assert(!out.isError && out.text == "(1,x)", s"tuple reply: $out")
  }

  // DEFERRED to 1.0.1 (C2.14), see above.
  test("java.time.DayOfWeek: the schema enumerates the seven names the decoder accepts") {
    pendingUntilFixed {
      val schema = h.inputSchema("dayofweek")
      val property = schema.asObject
        .flatMap(_.get("properties"))
        .flatMap(_.asObject)
        .flatMap(_.get("p"))
        .getOrElse(Json.Null)
      val names = MacroDxHarness.stringArray(property, "enum")
      assert(
        names.toSet == java.time.DayOfWeek.values.map(_.name).toSet,
        s"DayOfWeek schema advertises ${property.toJson}; enum = $names"
      )
    }
  }
