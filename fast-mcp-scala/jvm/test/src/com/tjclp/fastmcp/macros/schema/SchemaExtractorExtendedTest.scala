package com.tjclp.fastmcp.macros.schema

import org.scalatest.funsuite.AnyFunSuite

import com.tjclp.fastmcp.JsonTestSupport.*
import com.tjclp.fastmcp.macros.JsonSchemaMacro

import zio.json.*
import zio.json.ast.Json

/** Nested enum-schema coverage for the annotation path (native derivation). */
class SchemaExtractorExtendedTest extends AnyFunSuite {

  // Expanded enum test class
  enum PaymentMethod {
    case CreditCard, DebitCard, BankTransfer, PayPal, Crypto
  }

  // Case class with enum field
  case class Payment(amount: Double, method: PaymentMethod)

  // Enum behind Option, nested one level deeper (annotation-path TaskUpdate shape)
  case class PaymentUpdate(payment: Payment, fallback: Option[PaymentMethod])

  private def property(schema: Json, path: String*): Json =
    path.foldLeft(schema) { (node, key) =>
      node.hcursor.downField("properties").downField(key).focus.getOrElse(
        fail(s"missing property '$key' in ${node.toJson}")
      )
    }

  private def assertStringEnum(rawNode: Json, values: String*): Unit =
    // Option[enum] renders as a nullable anyOf — unwrap to the branch carrying the enum.
    val node = rawNode.hcursor.downField("anyOf").as[List[Json]].toOption match
      case Some(branches) =>
        branches.find(_.hcursor.downField("enum").succeeded).getOrElse(
          fail(s"no enum branch in anyOf: ${rawNode.toJson}")
        )
      case None => rawNode
    val tpe = node.hcursor.downField("type").as[String].toOption
    assert(tpe.contains("string"), s"expected string type in ${node.toJson}")
    val enumValues = node.hcursor.downField("enum").as[List[String]].getOrElse(Nil)
    values.foreach(v => assert(enumValues.contains(v), s"missing enum value $v in ${node.toJson}"))

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
