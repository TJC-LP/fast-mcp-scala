//> using scala "3.6.4"
//> using dep "dev.zio::zio:2.1.16"
//> using dep "dev.zio::zio-json:0.7.39"
//> using dep "io.modelcontextprotocol.sdk:mcp:0.8.1"
//> using repository "https://repo1.maven.org/maven2"
//> using file "src/main/scala"

// This is a launcher file for scala-cli
// You can run any of the MCP server classes with:
//
// scala-cli run main.scala --main-class fastmcp.examples.SimpleServer
// scala-cli run main.scala --main-class fastmcp.examples.AnnotatedServer
// scala-cli run main.scala --main-class fastmcp.FastMCPMain
//
// The stdout is redirected to stderr in each server implementation
// for MCP compatibility.
