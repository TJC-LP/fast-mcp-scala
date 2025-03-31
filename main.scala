//> using scala "3.6.4"
//> using dep "dev.zio::zio:2.1.16"
//> using dep "dev.zio::zio-json:0.7.39"
//> using dep "dev.zio::zio-schema:1.6.6"
//> using dep "dev.zio::zio-schema-json:1.6.6"
//> using dep "dev.zio::zio-schema-derivation:1.6.6"
//> using dep "io.modelcontextprotocol.sdk:mcp:0.8.1"
//> using dep "com.softwaremill.sttp.tapir::tapir-core:1.11.20"
//> using dep "com.softwaremill.sttp.tapir::tapir-apispec-docs:1.11.20"
//> using dep "com.softwaremill.sttp.tapir::tapir-json-circe:1.11.20"
//> using repository "https://repo1.maven.org/maven2"
//> using file "src/main/scala"

// This is a launcher file for scala-cli
// You can run any of the MCP server classes with:
//
// scala-cli run main.scala --main-class fastmcp.examples.SimpleServer       (Basic server example)
// scala-cli run main.scala --main-class fastmcp.examples.AnnotatedServer    (Annotation-based server)
// scala-cli run main.scala --main-class fastmcp.examples.TypedToolExample   (Enhanced typed tools example)
// scala-cli run main.scala --main-class fastmcp.examples.ZioSchemaToolExample (ZIO Schema integration example)
// scala-cli run main.scala --main-class fastmcp.FastMCPMain                 (Main application)
//
// The stdout is redirected to stderr in each server implementation
// for MCP compatibility.
