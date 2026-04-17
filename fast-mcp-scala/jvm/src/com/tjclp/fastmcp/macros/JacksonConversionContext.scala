package com.tjclp.fastmcp.macros

import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

import com.tjclp.fastmcp.core.McpDecodeContext

/** Shared Jackson 3-backed conversion helpers for low-level [[JacksonConverter]] implementations.
  */
final class JacksonConversionContext private[macros] (private val mapper: JsonMapper)
    extends McpDecodeContext:

  override def convertValue[T: ClassTag](name: String, rawValue: Any): T =
    val runtimeClass = summon[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
    try mapper.convertValue(rawValue, runtimeClass)
    catch
      case e: Exception =>
        throw new RuntimeException(
          s"Failed to convert value for parameter '$name' to type ${runtimeClass.getSimpleName}. Value: $rawValue",
          e
        )

  override def parseJsonArray(name: String, rawJson: String): List[Any] =
    try mapper.readValue(rawJson, classOf[java.util.List[Any]]).asScala.toList
    catch
      case e: Exception =>
        throw new RuntimeException(
          s"Failed to parse JSON array for parameter '$name'. Value: $rawJson",
          e
        )

  override def parseJsonObject(name: String, rawJson: String): Map[String, Any] =
    try mapper.readValue(rawJson, classOf[java.util.Map[String, Any]]).asScala.toMap
    catch
      case e: Exception =>
        throw new RuntimeException(
          s"Failed to parse JSON object for parameter '$name'. Value: $rawJson",
          e
        )

  override def writeValueAsString(value: Any): String =
    try mapper.writeValueAsString(value)
    catch
      case e: Exception =>
        throw new RuntimeException(s"Failed to write value as JSON. Value: $value", e)

  def rawMapper: JsonMapper = mapper

object JacksonConversionContext:

  lazy val default: JacksonConversionContext =
    new JacksonConversionContext(
      JsonMapper
        .builder()
        .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .build()
    )
