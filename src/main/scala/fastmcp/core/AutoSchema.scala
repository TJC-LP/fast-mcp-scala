package fastmcp.core

import io.circe.{Json, Printer}
import io.circe.syntax.*
import io.circe.generic.auto.*
import java.lang.{System => JSystem}
import scala.reflect.ClassTag

/**
 * Utility for generating JSON schemas from Scala types.
 * 
 * This is a simplified version that uses runtime reflection and manual schema building
 * rather than complex macros, making it more reliable across different Scala versions.
 */
object AutoSchema:
  /**
   * Generate a JSON Schema string for a type.
   * 
   * @tparam A The type to generate schema for
   * @return A JSON Schema string
   */
  def schemaFor[A: ClassTag]: String =
    // Get class from ClassTag
    val clazz = implicitly[ClassTag[A]].runtimeClass
    
    // Generate schema
    generateSchema(clazz)
  
  /**
   * Generate a schema for a class using runtime reflection.
   * 
   * @param clazz The class to generate schema for
   * @return JSON Schema as string
   */
  def generateSchema(clazz: Class[?]): String =
    // Check if it's a case class (approximate check)
    val isCaseClass = clazz.getMethods.exists(_.getName == "productArity")
    
    if !isCaseClass then
      return "{\"type\":\"object\"}"
    
    // Get fields
    val fields = clazz.getDeclaredFields
    
    // Build properties
    val properties = fields.map { field =>
      val name = field.getName
      
      // Default to string type for simplicity
      val schema = field.getType match
        case c if c == classOf[String] => Json.obj(
          "type" -> Json.fromString("string"),
          "description" -> Json.fromString(s"Parameter: $name")
        )
        case c if c == classOf[Int] || c == classOf[java.lang.Integer] ||
                 c == classOf[Long] || c == classOf[java.lang.Long] => Json.obj(
          "type" -> Json.fromString("integer"),
          "description" -> Json.fromString(s"Parameter: $name")
        )
        case c if c == classOf[Double] || c == classOf[java.lang.Double] ||
                 c == classOf[Float] || c == classOf[java.lang.Float] => Json.obj(
          "type" -> Json.fromString("number"),
          "description" -> Json.fromString(s"Parameter: $name")
        )
        case c if c == classOf[Boolean] || c == classOf[java.lang.Boolean] => Json.obj(
          "type" -> Json.fromString("boolean"),
          "description" -> Json.fromString(s"Parameter: $name")
        )
        case c if c.isArray => Json.obj(
          "type" -> Json.fromString("array"),
          "description" -> Json.fromString(s"Parameter: $name"),
          "items" -> Json.obj(
            "type" -> Json.fromString("string") // Default to string
          )
        )
        case c if c == classOf[scala.Option[?]] => Json.obj(
          "type" -> Json.fromString("string"),
          "nullable" -> Json.fromBoolean(true),
          "description" -> Json.fromString(s"Parameter: $name (optional)")
        )
        case _ => Json.obj(
          "type" -> Json.fromString("object"),
          "description" -> Json.fromString(s"Parameter: $name")
        )
      
      // Return name -> schema
      name -> schema
    }.toMap
    
    // Build schema
    val schemaJson = Json.obj(
      "type" -> Json.fromString("object"),
      "properties" -> Json.fromFields(properties),
      "required" -> Json.fromValues(fields.map(f => Json.fromString(f.getName)))
    )
    
    // Serialize schema
    val printer = Printer.noSpaces.copy(dropNullValues = true)
    printer.print(schemaJson)
  
  /**
   * Create a schema builder for a specified type.
   * 
   * @tparam A The type to create the schema builder for
   * @return A SchemaBuilder instance
   */
  def builder[A: ClassTag]: SchemaBuilder[A] =
    new SchemaBuilder[A]

  /**
   * Builder for constructing JSON schemas with fluent API.
   */
  class SchemaBuilder[A: ClassTag]:
    private var properties: Map[String, Json] = Map.empty
    private var required: List[String] = List.empty
    
    /**
     * Add a string property.
     */
    def addString(
      name: String,
      description: String,
      required: Boolean = true,
      format: Option[String] = None
    ): SchemaBuilder[A] =
      val schema = Json.obj(
        "type" -> Json.fromString("string"),
        "description" -> Json.fromString(description)
      )
      properties = properties + (name -> schema)
      if required then this.required = this.required :+ name
      this
    
    /**
     * Add a number property.
     */
    def addNumber(
      name: String,
      description: String,
      required: Boolean = true,
      minimum: Option[Double] = None,
      maximum: Option[Double] = None
    ): SchemaBuilder[A] =
      var schema = Json.obj(
        "type" -> Json.fromString("number"),
        "description" -> Json.fromString(description)
      )
      
      minimum.foreach { min =>
        schema = schema.deepMerge(Json.obj("minimum" -> Json.fromDoubleOrNull(min)))
      }
      
      maximum.foreach { max =>
        schema = schema.deepMerge(Json.obj("maximum" -> Json.fromDoubleOrNull(max)))
      }
      
      properties = properties + (name -> schema)
      if required then this.required = this.required :+ name
      this
    
    /**
     * Add an integer property.
     */
    def addInteger(
      name: String,
      description: String,
      required: Boolean = true,
      minimum: Option[Int] = None,
      maximum: Option[Int] = None
    ): SchemaBuilder[A] =
      var schema = Json.obj(
        "type" -> Json.fromString("integer"),
        "description" -> Json.fromString(description)
      )
      
      minimum.foreach { min =>
        schema = schema.deepMerge(Json.obj("minimum" -> Json.fromInt(min)))
      }
      
      maximum.foreach { max =>
        schema = schema.deepMerge(Json.obj("maximum" -> Json.fromInt(max)))
      }
      
      properties = properties + (name -> schema)
      if required then this.required = this.required :+ name
      this
    
    /**
     * Add a boolean property.
     */
    def addBoolean(
      name: String,
      description: String,
      required: Boolean = true
    ): SchemaBuilder[A] =
      val schema = Json.obj(
        "type" -> Json.fromString("boolean"),
        "description" -> Json.fromString(description)
      )
      properties = properties + (name -> schema)
      if required then this.required = this.required :+ name
      this
    
    /**
     * Add an enum property.
     */
    def addEnum(
      name: String,
      description: String,
      values: List[String],
      required: Boolean = true,
      defaultValue: Option[String] = None
    ): SchemaBuilder[A] =
      var schema = Json.obj(
        "type" -> Json.fromString("string"),
        "description" -> Json.fromString(description),
        "enum" -> Json.fromValues(values.map(Json.fromString))
      )
      
      defaultValue.foreach { default =>
        schema = schema.deepMerge(Json.obj("default" -> Json.fromString(default)))
      }
      
      properties = properties + (name -> schema)
      if required then this.required = this.required :+ name
      this
    
    /**
     * Build the schema.
     */
    def build(): String =
      val schemaJson = Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.fromFields(properties),
        "required" -> Json.fromValues(required.map(Json.fromString))
      )
      
      val printer = Printer.noSpaces.copy(dropNullValues = true)
      printer.print(schemaJson)