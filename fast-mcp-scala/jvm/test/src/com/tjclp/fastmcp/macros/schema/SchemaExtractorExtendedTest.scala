package com.tjclp.fastmcp.macros.schema

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.JsonTestSupport.*
import com.tjclp.fastmcp.macros.JsonSchemaMacro

/** Additional native enum-schema coverage.
  */
class SchemaExtractorExtendedTest extends AnyFunSuite {

  // Expanded enum test class
  enum PaymentMethod {
    case CreditCard, DebitCard, BankTransfer, PayPal, Crypto
  }

  // Case class with enum field
  case class Payment(amount: Double, method: PaymentMethod)

  // Enum behind Option, nested one level deeper (annotation-path TaskUpdate shape)
  case class PaymentUpdate(payment: Payment, fallback: Option[PaymentMethod])

  /** Resolve a property node, following a `$defs` `$ref` if tapir named the product schema. */
  private def property(schema: io.circe.Json, path: String*): io.circe.Json =
    def deref(node: io.circe.Json): io.circe.Json =
      node.hcursor.get[String]("$ref").toOption match
        case Some(ref) =>
          val name = ref.stripPrefix("#/$defs/")
          schema.hcursor.downField("$defs").downField(name).focus.getOrElse(node)
        case None => node
    path.foldLeft(schema) { (node, key) =>
      deref(node).hcursor.downField("properties").downField(key).focus.getOrElse(
        fail(s"missing property '$key' in ${deref(node).noSpaces}")
      )
    }

  private def assertStringEnum(rawNode: io.circe.Json, values: String*): Unit =
    // Option[enum] renders as a nullable anyOf under markOptionsAsNullable — unwrap to the
    // branch carrying the enum constraint.
    val node = rawNode.hcursor.downField("anyOf").as[List[io.circe.Json]].toOption match
      case Some(branches) =>
        branches.find(_.hcursor.downField("enum").succeeded).getOrElse(
          fail(s"no enum branch in anyOf: ${rawNode.noSpaces}")
        )
      case None => rawNode
    val tpe = node.hcursor.get[String]("type").toOption
      .orElse(node.hcursor.downField("type").as[List[String]].toOption.map(_.head))
    assert(tpe.contains("string"), s"expected string type in ${node.noSpaces}")
    val enumValues = node.hcursor.get[List[String]]("enum").getOrElse(Nil)
    values.foreach(v => assert(enumValues.contains(v), s"missing enum value $v in ${node.noSpaces}"))

  // Nested enum fields must render as string enums, not coproducts of empty objects (GH #78)
  test("nested enum field renders as a string enum schema") {
    def processPayment(payment: Payment): Unit = ()
    val schema = JsonSchemaMacro.schemaForFunctionArgs(processPayment)
    assert(schema.isObject)
    val method = property(schema, "payment", "method")
    assertStringEnum(method, "CreditCard", "PayPal", "Crypto")
  }

  test("enum behind Option and one level deeper also renders as a string enum") {
    def updatePayment(update: PaymentUpdate): Unit = ()
    val schema = JsonSchemaMacro.schemaForFunctionArgs(updatePayment)
    val nested = property(schema, "update", "payment", "method")
    assertStringEnum(nested, "DebitCard", "BankTransfer")
    val optional = property(schema, "update", "fallback")
    assertStringEnum(optional, "CreditCard", "Crypto")
  }
}
