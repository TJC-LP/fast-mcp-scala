//> using scala "3.6.4"
//> using dep "dev.zio::zio:2.1.17"
//> using dep "dev.zio::zio-json:0.7.39"
//> using dep "dev.zio::zio-schema:1.6.6"
//> using dep "dev.zio::zio-schema-json:1.6.6"
//> using dep "dev.zio::zio-schema-derivation:1.6.6"
//> using dep "io.modelcontextprotocol.sdk:mcp:0.8.1"
//> using dep "com.softwaremill.sttp.tapir::tapir-core:1.11.23"
//> using dep "com.softwaremill.sttp.tapir::tapir-apispec-docs:1.11.23"
//> using dep "com.softwaremill.sttp.tapir::tapir-json-circe:1.11.23"
//> using dep "com.softwaremill.sttp.apispec::jsonschema-circe:0.11.7"
//> using repository "https://repo1.maven.org/maven2"
//> using file "src/main/scala"
//> using options "-Xcheck-macros" // Enable verbose macro processing

// This is a launcher file for scala-cli
// You can run any of the MCP server classes with:
//
// scala-cli run main.scala --main-class fastmcp.examples.SimpleServer       (Basic server example)
// scala-cli run main.scala --main-class fastmcp.examples.AnnotatedServer    (Annotation-based server with macros)
// scala-cli run main.scala --main-class fastmcp.examples.TypedToolExample   (Enhanced typed tools example)
// scala-cli run main.scala --main-class fastmcp.examples.ZioSchemaToolExample (ZIO Schema integration example)
// scala-cli run main.scala --main-class fastmcp.examples.MacroSchemaExample  (Schema generation macro example)
// scala-cli run main.scala --main-class fastmcp.FastMCPMain                 (Main application)
//
// MACRO ENHANCEMENTS: 
// - The AnnotatedServer example demonstrates the enhanced macro-driven approach for
//   automatic schema generation and tool registration with @Tool annotations
// - The MacroSchemaExample shows the new direct schema generation capabilities using
//   SchemaMacros.schemaForFunctionArgs with Tapir integration
//
// These examples showcase how to use Scala 3's metaprogramming capabilities to create 
// a zero-boilerplate MCP tool registration experience with advanced schema generation.
//
// The stdout is redirected to stderr in each server implementation
// for MCP compatibility.
