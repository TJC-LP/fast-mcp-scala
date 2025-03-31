package fastmcp.core

import io.modelcontextprotocol.spec.McpSchema
import zio.schema._
import zio.schema.annotation._ // Import annotations like @description if needed later
import java.util.{Map => JMap, List => JList, HashMap => JHashMap, ArrayList => JArrayList}
import scala.jdk.CollectionConverters._
import java.lang.{System => JSystem} // Avoid conflict with zio.System

// Tapir imports
import sttp.tapir.{Schema => TapirSchema}
import sttp.tapir.SchemaType
import sttp.tapir.generic.auto._

/**
 * Generates McpSchema.JsonSchema instances from ZIO Schemas.
 * 
 * Maps ZIO Schema types to the basic structure supported by McpSchema.JsonSchema
 * (type, properties, items, required). More advanced JSON Schema features like
 * description, examples, enums, defaults, etc., are not directly supported by
 * the target McpSchema.JsonSchema class in the Java SDK.
 */
object SchemaGenerator {

  // Cache/Guard to handle recursive schemas
  private val recursionDepth = new ThreadLocal[Int] {
    override def initialValue(): Int = 0
  }
  private val maxRecursionDepth = 15 // Limit recursion depth

  /**
   * Entry point to generate an McpSchema.JsonSchema for a type A,
   * requiring an implicit ZIO Schema[A].
   * 
   * @tparam A The type to generate the schema for.
   * @param schema Implicit ZIO Schema for type A.
   * @return An McpSchema.JsonSchema representing type A.
   */
  def schemaFor[A](implicit schema: Schema[A]): McpSchema.JsonSchema = {
    fromZioSchema(schema)
  }

  /**
   * Recursively converts a ZIO Schema into an McpSchema.JsonSchema.
   * 
   * @param schema The ZIO Schema to convert.
   * @tparam A The underlying type of the schema.
   * @return The corresponding McpSchema.JsonSchema.
   */
  def fromZioSchema[A](schema: Schema[A]): McpSchema.JsonSchema = {
    // Prevent stack overflow for deeply recursive schemas
    if (recursionDepth.get() > maxRecursionDepth) {
      JSystem.err.println(s"[SchemaGenerator WARN] Max recursion depth ($maxRecursionDepth) reached for schema: $schema. Returning default object schema.")
      return defaultSchema()
    }

    recursionDepth.set(recursionDepth.get() + 1)
    try {
      schema match {
        // --- Primitive Types ---
        case Schema.Primitive(standardType, _) =>
          primitiveSchema(standardType)

        // --- Optional Type ---
        case Schema.Optional(innerSchema, _) =>
          // Optionality is handled by the parent Record (field won't be in 'required')
          // The schema type itself doesn't change.
          fromZioSchema(innerSchema)

        // --- Sequence Type (List, Seq, Array, etc.) ---
        case Schema.Sequence(elementSchema, _, _, _, _) =>
          val itemsSchema = fromZioSchema(elementSchema)
          createArraySchema(itemsSchema)

        // --- Record Type (Case Classes) ---
        case recordSchema: Schema.Record[A] =>
          val properties = new JHashMap[String, McpSchema.JsonSchema]()
          val requiredFields = new JArrayList[String]()

          recordSchema.fields.foreach { field =>
            val fieldSchema = field.schema
            // Check if the field's schema is Optional
            val isOptional = fieldSchema.isInstanceOf[Schema.Optional[?]]
            // Get the actual underlying schema if it's Optional
            val actualSchema = if (isOptional) fieldSchema.asInstanceOf[Schema.Optional[?]].schema else fieldSchema

            val propertySchema = fromZioSchema(actualSchema)
            
            // TODO: Extract annotations like @description if McpSchema.JsonSchema supports them
            // val description = field.annotations.collectFirst { case d: description => d.description }.orNull
            // propertySchema.description(description) // If setter existed

            properties.put(field.name, propertySchema)
            
            // Only non-optional fields are required in JSON Schema terms
            if (!isOptional) {
              requiredFields.add(field.name)
            }
          }
          createObjectSchema(properties, requiredFields)

        // --- Enum Type ---
        case enumSchema: Schema.Enum[A] =>
          // McpSchema.JsonSchema doesn't have an 'enum' field to list possible values.
          // Represent it as a string.
          // We could potentially add the possible values to a description field if it existed.
          // val possibleValues = enumSchema.cases.map(_.id).mkString(", ")
          createStringSchema()

        // --- Transform Type ---
        case Schema.Transform(codecSchema, _, _, _, _) =>
          // Generate schema based on the 'before' type (the one being decoded from JSON)
          fromZioSchema(codecSchema)

        // --- Lazy Type (for recursive schemas) ---
        case Schema.Lazy(lazySchemaThunk) =>
          // Recursively call on the underlying schema provided by the thunk
          fromZioSchema(lazySchemaThunk())

        // --- Fallback for other/unhandled ZIO Schema types ---
        case other =>
          JSystem.err.println(s"[SchemaGenerator WARN] Unsupported ZIO Schema type: ${other.getClass.getName}. Returning default 'object' schema.")
          defaultSchema()
      }
    } catch {
      // Catch potential errors during schema generation
      case e: Exception =>
        JSystem.err.println(s"[SchemaGenerator ERROR] Error generating schema for $schema: ${e.getMessage}")
        e.printStackTrace(JSystem.err) // Print stack trace for debugging
        defaultSchema() // Return a safe fallback
    }
    finally {
      // Ensure the recursion depth counter is decremented even if an error occurs
      recursionDepth.set(recursionDepth.get() - 1)
    }
  }

  // --- Helper methods for creating McpSchema.JsonSchema instances ---

  /** Maps ZIO StandardTypes to basic MCP JSON Schema types. */
  private def primitiveSchema(standardType: StandardType[?]): McpSchema.JsonSchema = standardType match {
    case StandardType.StringType        => createStringSchema()
    case StandardType.BoolType          => createBooleanSchema()
    case StandardType.IntType | StandardType.LongType |
         StandardType.ShortType | StandardType.ByteType |
         StandardType.BigIntegerType    => createIntSchema() // Using "integer" for all integral types
    case StandardType.DoubleType | StandardType.FloatType |
         StandardType.BigDecimalType    => createNumberSchema() // Using "number" for all decimal types
    
    // Represent temporal and UUID types as strings according to common JSON schema practice
    case StandardType.InstantType | StandardType.LocalDateType | StandardType.LocalTimeType |
         StandardType.LocalDateTimeType | StandardType.ZonedDateTimeType | StandardType.UUIDType |
         StandardType.DurationType      => createStringSchema()

    case StandardType.UnitType          => createNullSchema() // Represent Unit as null type
    
    // Fallback for other primitive types (e.g., CharType) - represent as string
    case _                              => createStringSchema()
  }

  private def createObjectSchema(properties: JMap[String, McpSchema.JsonSchema], required: JList[String]): McpSchema.JsonSchema = {
    // Review of constructor usage in FastMCPScala shows:
    // new McpSchema.JsonSchema("object", null, null, true)
    val schema = new McpSchema.JsonSchema("object", null, null, true) 
    
    // Now set properties and required fields
    // Converting to reflection-based approach to make it work
    try {
      val setPropertiesMethod = schema.getClass.getMethod("setProperties", classOf[JMap[?, ?]])
      setPropertiesMethod.invoke(schema, properties)
      
      if (required != null && !required.isEmpty) {
        val setRequiredMethod = schema.getClass.getMethod("setRequired", classOf[JList[?]])
        setRequiredMethod.invoke(schema, required)
      }
    } catch {
      case e: Exception =>
        JSystem.err.println(s"[SchemaGenerator ERROR] Error setting properties: ${e.getMessage}")
    }
    
    schema
  }

  private def createArraySchema(itemsSchema: McpSchema.JsonSchema): McpSchema.JsonSchema = {
    val schema = new McpSchema.JsonSchema("array", null, null, true)
    // Using reflection to set items
    try {
      val setItemsMethod = schema.getClass.getMethod("setItems", classOf[McpSchema.JsonSchema])
      setItemsMethod.invoke(schema, itemsSchema)
    } catch {
      case e: Exception =>
        JSystem.err.println(s"[SchemaGenerator ERROR] Error setting items: ${e.getMessage}")
    }
    schema
  }
  
  private def createNullSchema(): McpSchema.JsonSchema = {
    new McpSchema.JsonSchema("null", null, null, true)
  }


  /** Default fallback schema (empty object). */
  def defaultSchema(): McpSchema.JsonSchema =
    createObjectSchema(new JHashMap[String, McpSchema.JsonSchema](), new JArrayList[String]())

  /** Creates a basic 'integer' type schema. */
  def createIntSchema(): McpSchema.JsonSchema =
    new McpSchema.JsonSchema("integer", null, null, true)

  /** Creates a basic 'number' type schema. */
  def createNumberSchema(): McpSchema.JsonSchema =
    new McpSchema.JsonSchema("number", null, null, true)

  /** Creates a basic 'string' type schema. */
  def createStringSchema(): McpSchema.JsonSchema =
    new McpSchema.JsonSchema("string", null, null, true)

  /** Creates a basic 'boolean' type schema. */
  def createBooleanSchema(): McpSchema.JsonSchema =
    new McpSchema.JsonSchema("boolean", null, null, true)
    
  // ---------- Tapir Schema Support ----------

  /**
   * Generate McpSchema.JsonSchema from a Tapir Schema
   * 
   * @tparam A The type for which to generate a schema
   * @return McpSchema.JsonSchema for the given type
   */
  def schemaForTapir[A: TapirSchema]: McpSchema.JsonSchema = {
    // Get implicit Tapir schema
    val tapirSchema = implicitly[TapirSchema[A]]
    fromTapirSchema(tapirSchema)
  }

  /**
   * Convert a Tapir Schema to McpSchema.JsonSchema directly
   * This is a simplified implementation that infers basic schema types
   */
  def fromTapirSchema[A](schema: TapirSchema[A]): McpSchema.JsonSchema = {
    JSystem.err.println(s"[SchemaGenerator] Converting Tapir schema: $schema")
    
    // Very basic type inference - not comprehensive but works for common types
    val schemaType = schema.toString
    
    if (schemaType.contains("string") || schemaType.contains("String")) {
      return createStringSchema()
    } else if (schemaType.contains("integer") || schemaType.contains("Int") || schemaType.contains("Long")) {
      return createIntSchema()
    } else if (schemaType.contains("number") || schemaType.contains("Double") || schemaType.contains("Float")) {
      return createNumberSchema()
    } else if (schemaType.contains("boolean") || schemaType.contains("Boolean")) {
      return createBooleanSchema()
    } else if (schemaType.contains("array") || schemaType.contains("List") || schemaType.contains("Seq")) {
      return createArraySchema(createStringSchema()) // Simplified - using string items
    }
    
    // Create a default object schema for all complex types
    val properties = new JHashMap[String, McpSchema.JsonSchema]()
    val requiredFields = new JArrayList[String]()
    
    // For any non-primitive type, return a basic object schema
    createObjectSchema(properties, requiredFields)
  }
}