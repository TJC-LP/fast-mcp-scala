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

/** References two Java enum constants out of declaration order, BEFORE the macros below expand in
  * this file. The compiler lists a Java enum's constants in the order a run first touches them, so
  * a derivation that read that list would advertise `["DAYS", "MILLISECONDS", ...]` here (and did,
  * in one full build of this branch). The schema must be in declaration order regardless.
  */
object JavaEnumOrderProbe:
  val touchedFirst: java.util.concurrent.TimeUnit = java.util.concurrent.TimeUnit.DAYS
  val touchedSecond: java.util.concurrent.TimeUnit = java.util.concurrent.TimeUnit.MILLISECONDS

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

  // Tuples: productSchema advertises {"_1": ..., "_2": ...}, and that object is the one wire shape
  // of a tuple wherever it appears (C2.13). zio-json's own tuple decoder reads a JSON array; the
  // macro derives the product decoder instead, bare and inside Option / collections / case classes.
  @Tool(name = Some("tuple_plain"))
  def tuplePlain(@Param("p") p: (Int, String)): String = p.toString

  @Tool(name = Some("tuple_option"))
  def tupleOption(@Param("p", required = false) p: Option[(Int, String)]): String = p.toString

  @Tool(name = Some("tuple_list"))
  def tupleList(@Param("p") p: List[(Int, String)]): String = p.toString

  // A container SUBTYPE with a tuple inside: no structural arm builds a decoder for exactly that
  // type. It must compile (it stopped compiling when tuples got one wire shape; review of PR
  // #149) and read the tuple in the shape its schema advertises.
  @Tool(name = Some("tuple_indexed_seq"))
  def tupleIndexedSeq(@Param("p") p: IndexedSeq[(Int, String)]): String = p.toString

  @Tool(name = Some("tuple_sorted_set"))
  def tupleSortedSet(
      @Param("p") p: scala.collection.immutable.SortedSet[(Int, String)]
  ): String = p.toString

  @Tool(name = Some("tuple_map"))
  def tupleMap(@Param("p") p: Map[String, (Int, Color)]): String = p.toString

  @Tool(name = Some("tuple_nested"))
  def tupleNested(@Param("p") p: (Int, Option[(String, Boolean)])): String = p.toString

  @Tool(name = Some("tuple_field"))
  def tupleField(@Param("p") p: TupleHolder): String = p.toString

  // Java enums (C2.14): the schema enumerates the constant names and the decoder reads them.
  // `DayOfWeek` / `Month` use zio-json's decoders; any other Java enum gets a derived one.
  @Tool(name = Some("dayofweek"))
  def dayOfWeek(@Param("p") p: java.time.DayOfWeek): String = p.toString

  @Tool(name = Some("month"))
  def month(@Param("p") p: java.time.Month): String = p.toString

  @Tool(name = Some("timeunit"))
  def timeUnit(@Param("p") p: java.util.concurrent.TimeUnit): String = p.toString

  @Tool(name = Some("timeunit_list"))
  def timeUnitList(@Param("p") p: List[java.util.concurrent.TimeUnit]): String = p.toString

  @Tool(name = Some("timeunit_map"))
  def timeUnitMap(@Param("p") p: Map[String, java.util.concurrent.TimeUnit]): String = p.toString

  @Tool(name = Some("timeunit_field"))
  def timeUnitField(@Param("p") p: TimeUnitHolder): String = p.toString

/** A tuple type with a user-supplied decoder (its own wire shape: the string "tt" / "ff"). */
given userBooleanPairDecoder: JsonDecoder[(Boolean, Boolean)] = JsonDecoder.string.mapOrFail {
  case "tt" => Right((true, true))
  case "ff" => Right((false, false))
  case other => Left(s"expected tt or ff, got $other")
}

object UserTupleDecoderTools:

  @Tool(name = Some("user_tuple"))
  def userTuple(@Param("p") p: Option[(Boolean, Boolean)]): String = p.toString

/** A tuple as a case-class field, bare and optional. */
case class TupleHolder(pair: (Int, String), maybe: Option[(String, Color)])

/** A Java enum as a case-class field, bare and in a collection. */
case class TimeUnitHolder(
    unit: java.util.concurrent.TimeUnit,
    others: List[java.util.concurrent.TimeUnit]
)

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

  /** Live rows: `Either` of a derived payload in every wrapper (C2.3), tuples in every position
    * (C2.13) and Java enums (C2.14).
    */
  private val suspects =
    List(
      "either_plain",
      "either_list",
      "either_map",
      "either_option",
      "tuple_plain",
      "tuple_option",
      "tuple_list",
      "tuple_map",
      "tuple_nested",
      "tuple_field",
      "dayofweek",
      "month",
      "timeunit",
      "timeunit_list",
      "timeunit_map",
      "timeunit_field"
    )

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

  test("dropping a required key is rejected (non-Option parameters)") {
    val nonOption = (controls ++ suspects).filterNot(Set("either_option", "tuple_option"))
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

  test("Option[(Int, String)] decodes the same object as the bare tuple, and refuses an array") {
    val wrapped = h.call("tuple_option", """{"p":{"_1":1,"_2":"x"}}""")
    assert(!wrapped.isError && wrapped.text == "Some((1,x))", s"tuple_option reply: $wrapped")
    val omitted = h.call("tuple_option", "{}")
    assert(!omitted.isError && omitted.text == "None", s"tuple_option {} reply: $omitted")
    for tool <- List("tuple_plain", "tuple_option") do
      val array = h.call(tool, """{"p":[1,"x"]}""")
      assert(array.isError, s"$tool accepted the array shape its schema does not describe: $array")
  }

  test("a tuple inside a container subtype is read in the shape its schema advertises") {
    val empty = h.call("tuple_indexed_seq", """{"p":[]}""")
    assert(!empty.isError && empty.text == "Vector()", s"tuple_indexed_seq reply: $empty")
    // Input validation is on: these arguments pass the advertised schema, then the decoder.
    val seq = h.call("tuple_indexed_seq", """{"p":[{"_1":1,"_2":"a"},{"_1":2,"_2":"b"}]}""")
    assert(!seq.isError && seq.text == "Vector((1,a), (2,b))", s"tuple_indexed_seq reply: $seq")
    val sorted = h.call("tuple_sorted_set", """{"p":[{"_1":2,"_2":"b"},{"_1":1,"_2":"a"}]}""")
    assert(!sorted.isError && sorted.text == "TreeSet((1,a), (2,b))", s"tuple_sorted_set reply: $sorted")
    // The array shape of 1.0.x is not what the schema says, and is refused by validation.
    val array = h.call("tuple_indexed_seq", """{"p":[[1,"a"]]}""")
    assert(array.isError, s"the array shape was accepted: $array")
  }

  test("a tuple inside a collection or a case class is the same object") {
    val list = h.call("tuple_list", """{"p":[{"_1":1,"_2":"a"},{"_1":2,"_2":"b"}]}""")
    assert(!list.isError && list.text == "List((1,a), (2,b))", s"tuple_list reply: $list")
    val map = h.call("tuple_map", """{"p":{"k":{"_1":1,"_2":"RED"}}}""")
    assert(!map.isError && map.text == "Map(k -> (1,RED))", s"tuple_map reply: $map")
    val field = h.call(
      "tuple_field",
      """{"p":{"pair":{"_1":1,"_2":"a"},"maybe":{"_1":"b","_2":"BLUE"}}}"""
    )
    assert(
      !field.isError && field.text == "TupleHolder((1,a),Some((b,BLUE)))",
      s"tuple_field reply: $field"
    )
  }

  private def enumOf(tool: String, path: String*): List[String] =
    val property = path.foldLeft(h.inputSchema(tool)) { (schema, key) =>
      schema.asObject.flatMap(_.get(key)).getOrElse(Json.Null)
    }
    MacroDxHarness.stringArray(property, "enum")

  test("java.time.DayOfWeek and Month: the schema enumerates the names the decoder accepts") {
    assert(enumOf("dayofweek", "properties", "p") == java.time.DayOfWeek.values.map(_.name).toList)
    assert(enumOf("month", "properties", "p") == java.time.Month.values.map(_.name).toList)
    val ok = h.call("dayofweek", """{"p":"MONDAY"}""")
    assert(!ok.isError && ok.text == "MONDAY", s"dayofweek reply: $ok")
    val bad = h.call("dayofweek", """{"p":"x"}""")
    assert(bad.isError && bad.text.contains("must be one of"), s"dayofweek reply: $bad")
  }

  test("a Java enum derives a string-enum schema and a decoder by constant name") {
    val names = java.util.concurrent.TimeUnit.values.map(_.name).toList
    assert(enumOf("timeunit", "properties", "p") == names)
    assert(enumOf("timeunit_list", "properties", "p", "items") == names)
    assert(enumOf("timeunit_map", "properties", "p", "additionalProperties") == names)
    assert(enumOf("timeunit_field", "properties", "p", "properties", "unit") == names)

    val bare = h.call("timeunit", """{"p":"SECONDS"}""")
    assert(!bare.isError && bare.text == "SECONDS", s"timeunit reply: $bare")
    val list = h.call("timeunit_list", """{"p":["DAYS","HOURS"]}""")
    assert(!list.isError && list.text == "List(DAYS, HOURS)", s"timeunit_list reply: $list")
    val field = h.call("timeunit_field", """{"p":{"unit":"MINUTES","others":["DAYS"]}}""")
    assert(
      !field.isError && field.text == "TimeUnitHolder(MINUTES,List(DAYS))",
      s"timeunit_field reply: $field"
    )
    val unknown = h.call("timeunit", """{"p":"seconds"}""")
    assert(unknown.isError && unknown.text.contains("must be one of"), s"timeunit reply: $unknown")
  }

  test("a Java enum value outside the names is refused by the decoder when validation is off") {
    val server = com.tjclp.fastmcp.server.McpServer(
      "java-enum-no-validation",
      settings = com.tjclp.fastmcp.server.McpServerSettings(validateToolInput = false)
    )
    val _ = server.scanAnnotations[WireShapeTools.type]
    val refused = MacroDxHarness.runUnsafe(
      server.toolManager.callTool("timeunit", Map("p" -> Json.Str("seconds")), None).either
    )
    assert(
      refused.left.toOption.exists(_.getMessage.contains("expected one of NANOSECONDS")),
      s"got $refused"
    )
  }

  test("a JsonDecoder the user defines for a tuple type is used as written") {
    val server = com.tjclp.fastmcp.server.McpServer(
      "user-tuple-decoder",
      settings = com.tjclp.fastmcp.server.McpServerSettings(validateToolInput = false)
    )
    val _ = server.scanAnnotations[UserTupleDecoderTools.type]
    val decoded = MacroDxHarness.runUnsafe(
      server.toolManager.callTool("user_tuple", Map("p" -> Json.Str("tt")), None).either
    )
    assert(decoded == Right("Some((true,true))"), s"got $decoded")
  }
