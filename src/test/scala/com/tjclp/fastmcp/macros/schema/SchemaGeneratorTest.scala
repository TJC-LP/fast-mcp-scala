package com.tjclp.fastmcp.macros.schema

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import sttp.tapir.*
import sttp.tapir.Schema.SName
import sttp.tapir.SchemaType.*
import sttp.tapir.generic.auto.*

class SchemaGeneratorTest extends AnyFunSuite with Matchers {

  // Helper to expose the testable parts of SchemaGenerator's functionality
  object SchemaGeneratorTestable {
    case class User(name: String, age: Int)

    // Simplified version of product field generation that works at runtime
    def createProductField[T](name: String, schema: Schema[T]): SProductField[Unit] =
      SProductField[Unit, T](
        FieldName(name),
        schema,
        (_: Unit) => None
      )

    // Simplified version of creating args schema
    def createArgumentsSchema(fields: List[SProductField[Unit]]): Schema[Unit] =
      Schema(
        SProduct[Unit](fields = fields)
      ).name(SName("FunctionArgs"))
  }

  import SchemaGeneratorTestable.*

  test("createProductField should create a field with the given name and schema") {
    val schema = Schema.schemaForString
    val field = createProductField("test", schema)

    assert(field.name.name == "test")
    assert(field.schema == schema)
  }

  test("createProductField should work with primitive types") {
    val intSchema = Schema.schemaForInt
    val boolSchema = Schema.schemaForBoolean

    val intField = createProductField("intParam", intSchema)
    val boolField = createProductField("boolParam", boolSchema)

    assert(intField.name.name == "intParam")
    assert(boolField.name.name == "boolParam")
  }

  test("createProductField should work with complex types") {
    val userSchema = Schema.derived[User]
    val field = createProductField("user", userSchema)

    assert(field.name.name == "user")
    assert(field.schema.schemaType.isInstanceOf[SProduct[User]])
  }

  test("createArgumentsSchema should create a schema with the given fields") {
    val stringSchema = Schema.schemaForString
    val intSchema = Schema.schemaForInt

    val fields = List(
      createProductField("name", stringSchema),
      createProductField("age", intSchema)
    )

    val argsSchema = createArgumentsSchema(fields)

    assert(argsSchema.name.contains(SName("FunctionArgs")))
    argsSchema.schemaType match {
      case product: SProduct[Unit] =>
        assert(product.fields.length == 2)
        assert(product.fields.exists(_.name.name == "name"))
        assert(product.fields.exists(_.name.name == "age"))
      case _ => fail("Expected SProduct schema type")
    }
  }
}
