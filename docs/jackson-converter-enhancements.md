# JacksonConverter Enhancements

This document describes the enhancements made to the `JacksonConverter` trait in fast-mcp-scala to better handle complex classes natively.

## Enhanced Features

### 1. Collection Support
The library now includes enhanced converters for common collection types:

- **List/Seq**: Handles JSON strings, Java collections, arrays, and single elements
- **Map**: Supports both Scala and Java maps with flexible key-value conversion
- **Option**: Already supported, treats null/missing as None

### 2. Additional Type Converters
- **Boolean**: Flexible parsing supporting "true"/"false", "yes"/"no", "1"/"0", "on"/"off"
- **Long, Double, Float**: Native numeric type support

### 3. Custom Converter Creation
New helper methods for creating custom converters:

```scala
// Create converter from partial function
val myConverter = JacksonConverter.fromPartialFunction[MyType] {
  case str: String => MyType.parse(str)
  case map: Map[String, Any] => MyType.fromMap(map)
}

// Add custom Jackson module
val withModule = JacksonConverter.withCustomModule[MyType](myCustomModule)

// Transform input before conversion
val transformingConverter = existingConverter.contramap[String](_.toLowerCase)
```

### 4. Automatic Derivation
The new `DeriveJacksonConverter` macro automatically generates converters for case classes:

```scala
import com.tjclp.fastmcp.macros.DeriveJacksonConverter

case class Person(name: String, age: Int)

given JacksonConverter[Person] = DeriveJacksonConverter.derived[Person]
```

## Usage Examples

### Complex Filter Class
```scala
case class Filter(column: String, op: String, value: String)

// Automatic derivation
given JacksonConverter[Filter] = DeriveJacksonConverter.derived[Filter]

// Custom converter with flexible input
given JacksonConverter[Filter] = JacksonConverter.fromPartialFunction[Filter] {
  case map: Map[String, Any] =>
    Filter(
      column = map("column").toString,
      op = map.getOrElse("op", "=").toString,
      value = map("value").toString
    )
}
```

### Nested Collections
```scala
case class QueryFilters(filters: Seq[Filter])

// Automatically uses Seq[Filter] converter
given JacksonConverter[QueryFilters] = DeriveJacksonConverter.derived[QueryFilters]
```

### Transform Support
```scala
case class User(name: String, age: Int)

// Convert strings in "name:age" format
given JacksonConverter[User] = 
  DeriveJacksonConverter.derived[User].contramap[String] { str =>
    str.split(":") match
      case Array(name, age) => Map("name" -> name, "age" -> age.toInt)
      case _ => str // Let default converter handle it
  }
```

## Benefits

1. **Less Boilerplate**: No need to manually write converters for simple case classes
2. **Flexible Input Handling**: Support multiple input formats (JSON strings, Maps, etc.)
3. **Better Error Messages**: Include parameter names and types in error messages
4. **Composability**: Build complex converters from simple ones
5. **Type Safety**: Leverage Scala's type system while maintaining flexibility