package fastmcp.examples

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.{DefaultScalaModule, ClassTagExtensions}
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.DeserializationFeature

/**
 * Demonstrates the simple way to handle Scala 3 enums with Jackson
 * using the built-in DefaultScalaModule
 */
object JacksonEnumDemo {
  
  // Define some test enums
  enum Color:
    case RED, GREEN, BLUE
  
  enum TextStyle:
    case PLAIN, BOLD, ITALIC
    
    // You can also use JsonValue to customize serialization
    @JsonValue
    def toValue: String = this.toString.toLowerCase
  
  // A case class with enum fields
  case class StyledText(
    text: Option[String],
    color: Color,
    style: Option[TextStyle]
  )
  
  // Create the mapper with DefaultScalaModule
  val mapper = JsonMapper.builder()
    .addModule(DefaultScalaModule)
    .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
    .build() :: ClassTagExtensions
  
  def main(args: Array[String]): Unit = {
    println("=== Jackson Enum Demo with DefaultScalaModule ===")
    
    // Test 1: Serialize enum directly
    val color = Color.BLUE
    val colorJson = mapper.writeValueAsString(color)
    println(s"Serialized Color.BLUE: $colorJson")
    
    // Test 2: Deserialize enum directly
    val deserializedColor = mapper.readValue(colorJson, classOf[Color])
    println(s"Deserialized back to Color: $deserializedColor")
    
    // Test 3: Serialize enum with custom JsonValue
    val style = TextStyle.BOLD
    val styleJson = mapper.writeValueAsString(style)
    println(s"Serialized TextStyle.BOLD (with @JsonValue): $styleJson")
    
    // Test 4: Deserialize enum with custom JsonValue
    val deserializedStyle = mapper.readValue(styleJson, classOf[TextStyle])
    println(s"Deserialized back to TextStyle: $deserializedStyle")
    
    // Test 5: Case class with enum fields
    val styledText = StyledText(Some("Hello, World!"), Color.RED, Some(TextStyle.ITALIC))
    val styledTextJson = mapper.writeValueAsString(styledText)
    println(s"Serialized case class with enums: $styledTextJson")
    
    // Test 6: Deserialize case class with enum fields
    val deserializedStyledText = mapper.readValue(styledTextJson, classOf[StyledText])
    println(s"Deserialized case class: $deserializedStyledText")

    // Test 7: Deserialize with class tags
    val styledTextJson2 = """{"text":"Test","color":"RED","style":"ITALIC"}"""

    val deserializedStyledTextJson2 = mapper.readValue[StyledText](styledTextJson2)
    println(s"Deserialized with class tags: $deserializedStyledTextJson2")
  }
}