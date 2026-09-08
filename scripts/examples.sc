//> using scala 3.9.0
//> using dep com.tjclp::fast-mcp-scala:1.0.0-RC3
//> using options "-Xcheck-macros" "-experimental"

// Launcher for fast-mcp-scala example servers. Point `scala-cli` at this file and
// pick a main class:
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.HelloWorld
//     Minimum viable server — one tool, stdio.
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.AnnotatedServer
//     Flagship annotation path: @Tool / @Resource / @Prompt with hints and @Param metadata.
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.ContractServer
//     Typed contracts — macro-free, testable, cross-platform-shareable.
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.TaskManagerServer
//     Realistic domain server with custom decoders and tool hints across a CRUD-style surface.
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.ContextEchoServer
//     McpContext introspection from inside a tool handler.
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.HttpServer
//     HTTP transport: modern stateless POST + request-scoped SSE, with the legacy session adapter on by default.
//
// Nothing redirects stdout. On the stdio transport the JSON-RPC frame stream owns stdout, so
// anything a handler prints or logs there (ZIO's default logger included) corrupts the stream;
// write diagnostics to stderr instead. The library's own registration warnings already go there.
