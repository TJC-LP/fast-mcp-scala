package fastmcp.macros

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.{DeserializationFeature, JavaType, ObjectMapper, SerializationFeature} // Import JavaType
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, DefaultScalaModule}
import com.fasterxml.jackson.databind.`type`.TypeFactory // Import TypeFactory
import com.fasterxml.jackson.core.`type`.TypeReference

import scala.util.control.NonFatal
import scala.reflect.ClassTag // Keep ClassTag for potential other uses
import scala.jdk.CollectionConverters._

/**
 * Jackson utilities for handling serialization/deserialization.
 * Prioritizes using JavaType for runtime type information passed from macros.
 */
object JacksonUtils {

  // Create and configure a Jackson ObjectMapper with Scala support
  private val mapper: ObjectMapper = JsonMapper.builder()
    .addModule(new DefaultScalaModule())
    .addModule(new Jdk8Module())
    .addModule(new JavaTimeModule())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    // .configure(DeserializationFeature.READ_ENUMS_USING_TO_STRING, true) // Enable if needed
    .build()
    .setSerializationInclusion(JsonInclude.Include.NON_NULL) :: ClassTagExtensions

  // Expose TypeFactory for use in macro-generated code
  val typeFactory: TypeFactory = mapper.getTypeFactory

  /**
   * Convert an object to JSON string
   */
  def toJson(value: Any): String = {
    try {
      mapper.writeValueAsString(value)
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[JacksonUtils] Error converting value to JSON: $value - ${e.getMessage}")
        throw new RuntimeException(s"Failed to convert to JSON: ${e.getMessage}", e)
    }
  }

  /**
   * Convert from JSON string to an object described by the JavaType.
   * This is the preferred method for runtime deserialization when the
   * full generic type is known via reflection/macros.
   */
  def fromJson(json: String, javaType: JavaType): Any = {
    try {
      System.err.println(s"[JacksonUtils] Deserializing JSON: '$json' to JavaType: ${javaType.toCanonical}")
      val result = mapper.readValue(json, javaType)
      System.err.println(s"[JacksonUtils] Deserialized result: $result (${if (result != null) result.getClass.getName else "null"})")
      result
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[JacksonUtils] Error converting from JSON '$json' to JavaType ${javaType.toCanonical}: ${e.getMessage}")
        e.printStackTrace()
        throw new RuntimeException(s"Failed to convert from JSON to ${javaType.toCanonical}: ${e.getMessage}", e)
    }
  }

  /**
   * Convert between complex types (typically Map to target type) using a JavaType.
   * Relies on intermediate JSON serialization.
   */
  def convert(value: Any, targetType: JavaType): Any = {
    try {
      // Optimization: if value is already assignable, return it directly
      if (value != null && targetType.getRawClass.isInstance(value)) {
        System.err.println(s"[JacksonUtils] Convert: Value already assignable to ${targetType.toCanonical}. Skipping conversion.")
        value
      } else if (value == null && !targetType.isPrimitive && !targetType.isEnumType) { // Allow null for non-primitive, non-enum
        System.err.println(s"[JacksonUtils] Convert: Value is null, target type ${targetType.toCanonical} is nullable. Returning null.")
        null
      } else if (value == null && (targetType.isPrimitive || targetType.isEnumType)) { // Disallow null for primitives and enums
        throw new RuntimeException(s"Cannot convert null to primitive or enum type: ${targetType.toCanonical}")
      }
      // Special handling for enum types
      else if (value.isInstanceOf[String] && (targetType.isEnumType || 
              targetType.getRawClass.getName.contains("$") && 
              (targetType.getRawClass.getDeclaredFields.exists(_.getName == "$values") ||
              targetType.getRawClass.getDeclaredMethods.exists(_.getName == "values")))) {
        // Try direct enum conversion first
        convertStringToEnum(value.asInstanceOf[String], targetType.getRawClass)
          .getOrElse {
            // Fallback to JSON conversion
            System.err.println(s"[JacksonUtils] No direct enum match found, using JSON for conversion")
            val json = toJson(value)
            fromJson(json, targetType)
          }
      }
      // Special case for case class conversion from Map to case class directly
      else if (value.isInstanceOf[Map[?, ?]] && !targetType.getRawClass.isAssignableFrom(classOf[Map[?, ?]])) {
        try {
          System.err.println(s"[JacksonUtils] Converting Map to case class ${targetType.getRawClass.getName}")
          // First try to instantiate a case class via reflection if possible
          val fields = targetType.getRawClass.getDeclaredFields
          val fieldNames = fields.map(_.getName).toSet
          val map = value.asInstanceOf[Map[?, ?]]
          
          // Check if fields match enough to try direct case class instantiation
          val matchingFieldCount = map.keys.count(k => fieldNames.contains(k.toString))
          
          if (matchingFieldCount > 0 && fields.exists(f => f.getName == "apply$default$1")) {
            System.err.println(s"[JacksonUtils] Trying direct case class instantiation via companion apply method")
            // Get companion object's apply method
            val companionObjClass = targetType.getRawClass.getDeclaredClasses.find(_.getName.endsWith("$"))
            companionObjClass.flatMap { companionClass =>
              // Get the companion object instance 
              try {
                val moduleField = companionClass.getDeclaredField("MODULE$")
                val companionObj = moduleField.get(null)
                
                // Find an apply method with the right number of parameters
                companionClass.getMethods
                  .find(m => m.getName == "apply" && m.getParameterCount <= map.size)
                  .map { applyMethod =>
                    // Prepare arguments
                    val paramNames = applyMethod.getParameters.map(_.getName)
                    val args = new Array[AnyRef](paramNames.length)
                    paramNames.zipWithIndex.foreach { case (name, i) => 
                      args(i) = map.asInstanceOf[Map[Any, Any]].getOrElse(name, null).asInstanceOf[AnyRef]
                    }
                    
                    // Invoke apply method
                    applyMethod.invoke(companionObj, args*)
                  }
              } catch {
                case e: Exception =>
                  System.err.println(s"[JacksonUtils] Error during direct case class instantiation: ${e.getMessage}")
                  None
              }
            }.getOrElse {
              // Fallback to JSON serialization
              System.err.println(s"[JacksonUtils] Falling back to JSON serialization for case class")
              val json = toJson(value)
              fromJson(json, targetType)
            }
          } else {
            // Use JSON conversion
            System.err.println(s"[JacksonUtils] Using JSON conversion for case class: ${targetType.getRawClass.getName}")
            val json = toJson(value)
            fromJson(json, targetType)
          }
        } catch {
          case e: Exception =>
            System.err.println(s"[JacksonUtils] Error during case class conversion: ${e.getMessage}")
            // Fallback to standard JSON conversion
            val json = toJson(value)
            fromJson(json, targetType)
        }
      }
      else {
        System.err.println(s"[JacksonUtils] Converting value '$value' (${value.getClass.getName}) to JavaType ${targetType.toCanonical} via JSON")
        val json = toJson(value) // Serialize the source value
        fromJson(json, targetType) // Deserialize to the target type using JavaType
      }
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[JacksonUtils] Error converting value '$value' to type ${targetType.toCanonical}: ${e.getMessage}")
        throw new RuntimeException(s"Failed to convert value to ${targetType.toCanonical}: ${e.getMessage}", e)
    }
  }

  /**
   * Try to convert a string directly to an enum value using various approaches
   */
  def convertStringToEnum(str: String, enumClass: Class[?]): Option[AnyRef] = {
    try {
      System.err.println(s"[JacksonUtils] Attempting to convert string '$str' to enum type ${enumClass.getName}")
      
      val upperStr = str.toUpperCase()
      val lowerStr = str.toLowerCase()
      
      // For Java-style enums, this is the preferred approach
      if (enumClass.isEnum) {
        val enumValues = enumClass.getEnumConstants
        
        // Try direct, uppercase and lowercase matches
        enumValues.collectFirst {
          case v if v.toString == str => v
          case v if v.toString == upperStr => v 
          case v if v.toString == lowerStr => v
          case v if v.toString.equalsIgnoreCase(str) => v
        }.map(_.asInstanceOf[AnyRef]).orElse {
          // Try the standard valueOf
          try {
            val valueOfMethod = enumClass.getMethod("valueOf", classOf[String])
            Some(valueOfMethod.invoke(null, str))
          } catch {
            case _: Exception => 
              try {
                val valueOfMethod = enumClass.getMethod("valueOf", classOf[String])
                Some(valueOfMethod.invoke(null, upperStr))
              } catch {
                case _: Exception => None
              }
          }
        }
      }
      // For Scala 3 enums
      else if (enumClass.getName.contains("$") && 
              (enumClass.getDeclaredFields.exists(_.getName == "$values") || 
               enumClass.getDeclaredMethods.exists(_.getName == "values"))) {
        
        // Get enum values
        val valuesMethod = try {
          enumClass.getMethod("values")
        } catch {
          case _: NoSuchMethodException => 
            enumClass.getDeclaringClass.getMethod("values")
        }
        
        val values = valuesMethod.invoke(null).asInstanceOf[Array[Object]]
        
        // Try matching by toString
        values.collectFirst {
          case v if v.toString == str => v
          case v if v.toString == upperStr => v
          case v if v.toString == lowerStr => v 
          case v if v.toString.equalsIgnoreCase(str) => v
        }.map(_.asInstanceOf[AnyRef]).orElse {
          // Try valueOf
          try {
            val enclosingClass = if (enumClass.getDeclaringClass != null) enumClass.getDeclaringClass else enumClass
            val valueOfMethod = enclosingClass.getMethod("valueOf", classOf[String])
            Some(valueOfMethod.invoke(null, str))
          } catch {
            case e: Exception =>
              try {
                val valueOfMethod = try {
                  val enclosingClass = if (enumClass.getDeclaringClass != null) enumClass.getDeclaringClass else enumClass
                  enclosingClass.getMethod("valueOf", classOf[String])
                } catch {
                  case _: Exception => null
                }
                if (valueOfMethod == null) None else
                Some(valueOfMethod.invoke(null, upperStr))
              } catch {
                case e: Exception => 
                  // Try accessing as a field
                  try {
                    val enclosingClass = if (enumClass.getDeclaringClass != null) enumClass.getDeclaringClass else enumClass
                    val field = try {
                      enclosingClass.getField(str)
                    } catch {
                      case _: NoSuchFieldException => 
                        try { enclosingClass.getField(upperStr) }
                        catch { case _: NoSuchFieldException => enclosingClass.getField(lowerStr) }
                    }
                    Some(field.get(null))
                  } catch {
                    case e: Exception => None
                  }
              }
          }
        }
      } else {
        None
      }
    } catch {
      case e: Exception =>
        System.err.println(s"[JacksonUtils] Error during enum conversion: ${e.getMessage}")
        None
    }
  }

  // Optional: Keep ClassTag version for simple cases if desired
  def fromJson[T: ClassTag](json: String): T = {
    val javaType = typeFactory.constructType(implicitly[ClassTag[T]].runtimeClass)
    fromJson(json, javaType).asInstanceOf[T]
  }

  def convert[T: ClassTag](value: Any): T = {
    val javaType = typeFactory.constructType(implicitly[ClassTag[T]].runtimeClass)
    convert(value, javaType).asInstanceOf[T]
  }
}