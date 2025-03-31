package fastmcp.core

import io.circe.{Json, Printer}
import io.circe.syntax.*
import java.lang.{System => JSystem}

/**
 * Utilities for building JSON Schema strings.
 * 
 * This is a simpler approach than using full reflection or macros,
 * allowing manual construction of schema strings with type-safe builders.
 */
object JsonSchemaBuilder:
  /**
   * Examples of creating schema strings for common types.
   * These are convenience methods for manual schema creation.
   */
  object Examples:
    /**
     * Create a string property schema.
     */
    def stringProperty(
      description: String,
      required: Boolean = true,
      format: Option[String] = None,
      example: Option[String] = None
    ): Map[String, Any] =
      buildPropertySchema("string", description, required, format, example)
    
    /**
     * Create a number property schema.
     */
    def numberProperty(
      description: String,
      required: Boolean = true,
      minimum: Option[Double] = None,
      maximum: Option[Double] = None,
      example: Option[Double] = None
    ): Map[String, Any] =
      val baseSchema = buildPropertySchema("number", description, required, None, example.map(_.toString))
      val withMin = minimum.fold(baseSchema)(min => baseSchema + ("minimum" -> min))
      maximum.fold(withMin)(max => withMin + ("maximum" -> max))
    
    /**
     * Create an integer property schema.
     */
    def integerProperty(
      description: String,
      required: Boolean = true,
      minimum: Option[Int] = None,
      maximum: Option[Int] = None,
      example: Option[Int] = None
    ): Map[String, Any] =
      val baseSchema = buildPropertySchema("integer", description, required, None, example.map(_.toString))
      val withMin = minimum.fold(baseSchema)(min => baseSchema + ("minimum" -> min))
      maximum.fold(withMin)(max => withMin + ("maximum" -> max))
    
    /**
     * Create a boolean property schema.
     */
    def booleanProperty(
      description: String,
      required: Boolean = true,
      example: Option[Boolean] = None
    ): Map[String, Any] =
      buildPropertySchema("boolean", description, required, None, example.map(_.toString))
    
    /**
     * Create an enum property schema.
     */
    def enumProperty(
      description: String,
      values: List[String],
      required: Boolean = true,
      defaultValue: Option[String] = None,
      example: Option[String] = None
    ): Map[String, Any] =
      val baseSchema = buildPropertySchema("string", description, required, None, example)
      val withEnum = baseSchema + ("enum" -> values)
      defaultValue.fold(withEnum)(default => withEnum + ("default" -> default))
    
    /**
     * Helper method to build a base property schema.
     */
    private def buildPropertySchema(
      typeName: String,
      description: String,
      required: Boolean,
      format: Option[String],
      example: Option[String]
    ): Map[String, Any] =
      var schema = Map[String, Any](
        "type" -> typeName,
        "description" -> description
      )
      
      if !required then
        schema = schema + ("nullable" -> true)
      
      format.foreach(f => schema = schema + ("format" -> f))
      example.foreach(e => schema = schema + ("example" -> e))
      
      schema
  end Examples

  /**
   * Creates a JSON Schema string for a basic case class.
   * This provides a way to manually specify the schema structure until
   * the automated reflection-based generation is complete.
   * 
   * @param typeName The type name (e.g., "object", "string", etc.)
   * @param properties Map of property names to their schema definitions
   * @param required List of required property names
   * @return A JSON Schema string
   */
  def createSchema(
    typeName: String, 
    properties: Map[String, Map[String, Any]],
    required: List[String] = List.empty
  ): String =
    // Convert properties to JSON
    val jsonProperties = properties.map { case (name, schema) =>
      name -> propertyMapToJson(schema)
    }
    
    // Build the schema JSON
    val schemaJson = Json.obj(
      "type" -> Json.fromString(typeName),
      "properties" -> Json.fromFields(jsonProperties),
      "required" -> Json.fromValues(required.map(Json.fromString))
    )
    
    // Convert to compact string
    val printer = Printer.noSpaces.copy(dropNullValues = true)
    printer.print(schemaJson)
  
  /**
   * Convert a property map to JSON.
   */
  private def propertyMapToJson(schema: Map[String, Any]): Json =
    Json.fromFields(schema.map { case (k, v) => k -> propertyToJson(v) })

  /**
   * Convert a property definition to a JSON value.
   * 
   * @param value The property schema definition
   * @return The JSON representation
   */
  private def propertyToJson(value: Any): Json =
    value match {
      case s: String => Json.fromString(s)
      case n: Int => Json.fromInt(n)
      case n: Long => Json.fromLong(n)
      case n: Double => Json.fromDoubleOrString(n)
      case b: Boolean => Json.fromBoolean(b)
      case null => Json.Null
      case m: Map[?, ?] => 
        val stringMap = m.asInstanceOf[Map[String, Any]]
        Json.fromFields(stringMap.map { case (k, v) => k -> propertyToJson(v) })
      case s: Seq[?] => Json.fromValues(s.map(propertyToJson))
      case _ => Json.fromString(value.toString)
    }