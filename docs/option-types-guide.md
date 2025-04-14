# Working with Optional Parameters in FastMCP Scala

This guide provides information on how to effectively use optional parameters with FastMCP Scala.

## Using Option Types for Optional Parameters

FastMCP Scala automatically detects Option types and marks them as non-required in the generated JSON Schema. This is the recommended approach for indicating optional parameters.

### Function Parameters

When defining functions that will be exposed as tools, use `Option[T]` for optional parameters:

```scala
// Function with both required and optional parameters
def searchUsers(
  query: String,                   // Required parameter
  maxResults: Option[Int],         // Optional parameter (no default)
  includeInactive: Option[Boolean] = None  // Optional parameter with default
): String = {
  // Implementation
  val maxResultsValue = maxResults.getOrElse(10)
  val includeInactiveValue = includeInactive.getOrElse(false)
  // ...
}
```

In the example above:
- `query` is a required parameter and will be marked as required in the schema
- `maxResults` is an optional parameter with no default (not marked as required in schema)
- `includeInactive` is an optional parameter with a default value of `None` (not marked as required in schema)

### Option Types with Default Values

You can also provide a default value other than `None` for optional parameters:

```scala
def formatText(
  text: String,                                // Required
  format: Option[String] = Some("plain"),      // Optional with "plain" default
  lineLength: Option[Int] = Some(80),          // Optional with 80 default
  capitalize: Option[Boolean] = Some(false)    // Optional with false default
): String = {
  // Implementation
  val formatValue = format.getOrElse("plain")
  val lineLengthValue = lineLength.getOrElse(80)
  val capitalizeValue = capitalize.getOrElse(false)
  // ...
}
```

### Case Classes with Optional Fields

When defining case classes that will be used as parameter types, use `Option[T]` for fields that should be optional:

```scala
case class UserProfile(
  id: String,                        // Required
  username: String,                  // Required
  email: Option[String],             // Optional (no default)
  age: Option[Int] = Some(18),       // Optional with default
  preferences: Option[Map[String, String]] = Some(Map.empty) // Optional with default
)
```

## Schema Generation

FastMCP Scala's `JsonSchemaMacro` will automatically mark Option fields as non-required in the JSON schema:

```scala
import fastmcp.macros.JsonSchemaMacro

val schema = JsonSchemaMacro.schemaForFunctionArgs(searchUsers)
println(schema.spaces2)
```

In the generated schema:
- The `required` array will include non-Option parameters (`query`) 
- Option parameters (`maxResults`, `includeInactive`) will not be included in the `required` array

## Map to Function Conversion

The `MapToFunctionMacro` properly handles Option types when converting from a Map to your function:

```scala
import fastmcp.macros.MapToFunctionMacro

val mapToFn = MapToFunctionMacro.callByMap(searchUsers)

// With all parameters
val result1 = mapToFn(Map(
  "query" -> "test", 
  "maxResults" -> 20, 
  "includeInactive" -> true
))

// With only required parameters
val result2 = mapToFn(Map("query" -> "test"))
// Optional parameters will be None in the function call
```

## Best Practices

1. **Use Option, not default values:** 
   - Always use `Option[T]` for optional parameters instead of relying on default values
   - The schema generator reliably detects Option types but cannot detect parameters with default values

2. **Provide defaults with Some:**
   - For parameters that should have a default value, use `Option[T] = Some(defaultValue)`
   - This clearly communicates both optionality and the default value

3. **Handle Option values defensively:**
   - Use `getOrElse` or pattern matching to safely extract values from Option parameters
   - For nested Options (like parameters in case classes), use flatMap or for-comprehensions

4. **Document parameter optionality:**
   - Use the `@Param` annotation with `required = false` for optional parameters
   - Include information about default values in the description

Example with annotations:

```scala
@Tool(name = "search", description = "Search for users")
def searchUsers(
  @Param(description = "Search query", required = true)
  query: String,
  
  @Param(description = "Maximum results to return (default: 10)", required = false)
  maxResults: Option[Int] = None,
  
  @Param(description = "Include inactive users (default: false)", required = false)
  includeInactive: Option[Boolean] = None
): String = {
  // Implementation
}
```