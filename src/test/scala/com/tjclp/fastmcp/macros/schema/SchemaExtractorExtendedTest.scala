package com.tjclp.fastmcp.macros.schema

import com.tjclp.fastmcp.macros.JsonSchemaMacro
import org.scalatest.funsuite.AnyFunSuite
import sttp.tapir.generic.auto.*

/** Simple tests for SchemaExtractor functionality to increase coverage
  */
class SchemaExtractorExtendedTest extends AnyFunSuite {

  // Expanded enum test class
  enum PaymentMethod {
    case CreditCard, DebitCard, BankTransfer, PayPal, Crypto
  }

  // Case class with enum field
  case class Payment(amount: Double, method: PaymentMethod)

  // Just verify that the macro runs successfully and gets us a JSON object back
  test("Schema extraction should handle complex enums") {
    def processPayment(payment: Payment): Unit = ()
    val schema = JsonSchemaMacro.schemaForFunctionArgs(processPayment)
    assert(schema.isObject)
  }
}
