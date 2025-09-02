//> using scala 3.7.2
//> using dep com.tjclp::fast-mcp-scala:0.1.2
//> using options "-Xcheck-macros" "-experimental" // Enable verbose macro processing

// This is a launcher file for scala-cli
// You can run any of the MCP server classes with:
//
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.SimpleServer       (Basic server example)
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.AnnotatedServer    (Annotation-based server with macros)
// scala-cli examples.sc --main-class com.tjclp.fastmcp.examples.ManualServer       (Manual tool registration example)
//
// MACRO ENHANCEMENTS:
// - The AnnotatedServer example demonstrates the enhanced macro-driven approach for
//   automatic schema generation and tool registration with @Tool annotations
// - The ManualServer example shows manual tool registration with enum conversion through Jackson
//
// These examples showcase how to use Scala 3's metaprogramming capabilities to create
// a zero-boilerplate MCP tool registration experience with advanced schema generation.
//
// The stdout is redirected to stderr in each server implementation
// for MCP compatibility.
