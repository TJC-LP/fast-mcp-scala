//> using scala 3.8.3
//> using dep com.tjclp::fast-mcp-scala:0.3.0-rc2
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
//     Realistic domain server with custom JacksonConverters and tool hints.
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.ContextEchoServer
//     McpContext introspection from inside a tool handler.
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.HttpServer
//     HTTP transport: Streamable (default) and Stateless (flag).
//
// stdout is redirected to stderr inside each server so the stdio transport stays clean.
