# FastMCP-Scala Implementation Guide for AI Agent

## 1. Goal

The primary objective is to create `fast-mcp-scala`, a Scala 3 library that provides a high-level, developer-friendly API for building Model Context Protocol (MCP) servers. This library should:

1.  **Mimic Python `fastmcp`:** Offer a similar user experience using Scala 3 features like annotations (`@Tool`, `@Resource`, `@Prompt`) instead of Python decorators.
2.  **Integrate Java MCP SDK:** Utilize the `io.modelcontextprotocol.sdk` (`java-sdk`) for the underlying MCP protocol implementation, transport handling, and session management.
3.  **Leverage ZIO:** Employ the ZIO library for functional effects, asynchronous operations, resource management, and robust error handling.
4.  **Utilize Scala 3:** Take advantage of features like extension methods, context functions, macros (preferred) or reflection, enums, and union types.

## 2. Project Setup (`build.sbt`)

The `build.sbt` file needs to declare the necessary dependencies.

*   **Scala Version:** Ensure Scala 3 (e.g., 3.6.4 or later) is set.
*   **ZIO:** Include `zio` and `zio-json` for effects and JSON handling.
*   **Java MCP SDK:** Add the `io.modelcontextprotocol.sdk:mcp` artifact. Define a version variable (e.g., `mcpJavaSdkVersion`).

```sbt
// build.sbt
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.6.4"

val zioVersion        = "2.1.16"
val mcpJavaSdkVersion = "0.8.1" // Or the latest stable version

lazy val root = (project in file("."))
  .settings(
    name := "fast-mcp-scala",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-json" % "0.7.39", // For JSON schema and potentially data handling
      "io.modelcontextprotocol.sdk" % "mcp" % mcpJavaSdkVersion
    ),
    // Add compiler options for macros if needed later
    // scalacOptions ++= Seq("-Xmacros:enable") // Example
  )
```

## 3. Core Data Types (`fastmcp.core.Types`)

Define Scala case classes and enums corresponding to the fundamental MCP concepts found in `McpSchema.java` and `fastmcp/prompts/base.py`, `fastmcp/resources/base.py`, `fastmcp/tools/base.py`.

*   **Key Types:** `ToolDefinition`, `ResourceDefinition`, `PromptDefinition`, `PromptArgument`, `Content` (sealed trait with `TextContent`, `ImageContent`, `EmbeddedResource`), `Message`, `Role` (enum).
*   **Interoperability:** Implement `toJava` methods (or implicit conversions) for each Scala type to convert it to its corresponding `io.modelcontextprotocol.spec.McpSchema` Java type. Use `scala.jdk.CollectionConverters` for lists/maps.
*   **JSON Handling:** Add `given JsonCodec[...] = DeriveJsonCodec.gen[...]` using `zio-json` for each case class and sealed trait. This will be crucial for handling tool inputs and potentially prompt arguments/resource content.
*   **ToolInputSchema:** Represent the `inputSchema` for tools. Initially, this could use the Java `McpSchema.JsonSchema` type directly, or define a Scala structure that can be easily converted. Eventually, leverage `zio-json`'s schema generation capabilities if possible, or integrate a dedicated JSON Schema library.
*   **Content Types:** Ensure the `Content` sealed trait and its subtypes (`TextContent`, `ImageContent`, `EmbeddedResource`) correctly map to the Java SDK equivalents, including the `type` field discrimination used by Jackson in the Java SDK. The `EmbeddedResource` should contain an `EmbeddedResourceContent` which maps to `McpSchema.ResourceContents`.

**Example Snippet (`Types.scala`):**

```scala
package fastmcp.core

import zio.json.*
import io.modelcontextprotocol.spec.McpSchema
import scala.jdk.CollectionConverters.*

// ... (other types)

sealed trait Content(`type`: String):
  def toJava: McpSchema.Content

object Content:
  given JsonCodec[TextContent] = DeriveJsonCodec.gen[TextContent]
  // ... codecs for other content types ...
  given JsonCodec[Content] = DeriveJsonCodec.gen[Content] // For the sealed trait

case class TextContent(...) extends Content("text"):
  override def toJava: McpSchema.TextContent = ... // conversion logic

// ... (Message, Role with codecs and toJava)
```

## 4. Annotations (`fastmcp.core.Annotations`)

Define simple marker annotations extending `scala.annotation.StaticAnnotation`.

*   `@Tool(name: Option[String], description: Option[String])`
*   `@Resource(uri: String, name: Option[String], description: Option[String], mimeType: Option[String])`
*   `@Prompt(name: Option[String], description: Option[String])`

These annotations will be processed by the metaprogramming layer.

## 5. Metaprogramming Strategy (Macros Preferred)

This is the core of the "FastMCP" developer experience. The goal is to automatically convert annotated Scala methods into MCP definitions and register them.

*   **Approach:** Use Scala 3 compile-time macros (`inline def` with macro implementations). This provides type safety and avoids runtime reflection overhead. Runtime reflection is a less desirable fallback.
*   **Macro Tasks:**
    1.  **Identify Annotations:** Find methods annotated with `@Tool`, `@Resource`, or `@Prompt` within the `FastMCPScala` class or companion object (or potentially anywhere, depending on design).
    2.  **Extract Metadata:** For each annotated method:
        *   Get the method name (use annotation `name` if provided, else default to method name).
        *   Get the description (use annotation `description` if provided, else use Scaladoc).
        *   Analyze the method signature (parameter names, types, default values).
        *   Determine if the method is ZIO-based (returns `ZIO[R, E, A]`) or synchronous.
        *   Identify if a `Context` parameter is present.
    3.  **Generate Definitions:** Create instances of `ToolDefinition`, `ResourceDefinition`, or `PromptDefinition` based on the extracted metadata.
        *   For `@Tool`: Generate the `inputSchema` (e.g., using `zio-json`'s schema derivation for a case class derived from the parameters, or by mapping parameters directly to a `McpSchema.JsonSchema`).
        *   For `@Resource` (template): Extract URI parameters and validate against function parameters. Store function reference.
        *   For `@Resource` (static): Store function reference.
        *   For `@Prompt`: Generate `PromptArgument` list from method parameters. Store function reference.
    4.  **Generate Handlers:** Create wrapper functions (likely returning `ZIO[Any, Throwable, Any]`) that:
        *   Handle argument parsing/validation (potentially using `zio-json` or generated Pydantic-like models).
        *   Inject the `Context` object if requested by the original method signature.
        *   Call the original user-defined method.
        *   Handle ZIO effects (`map`, `flatMap`, `catchAll`).
        *   Convert the return value to the expected type (e.g., `String | Array[Byte]` for resources, `List[Content]` for tools, `List[Message]` for prompts).
    5.  **Register:** Generate code that calls the appropriate `addTool`, `addResource/addTemplate`, `addPrompt` method on the corresponding manager instance (`ToolManager`, `ResourceManager`, `PromptManager`) during server initialization.
*   **Implementation:** This will likely involve defining macro implementations in a separate module or using `inline def` directly within the `FastMCPScala` class or companion object for the decorator methods (`tool`, `resource`, `prompt`).

## 6. Manager Classes (`ToolManager`, `ResourceManager`, `PromptManager`)

These classes manage the registered definitions and their associated handler logic.

*   **Storage:** Use Scala mutable or immutable maps (consider thread-safety if runtime registration is allowed, though ZIO's single-threaded nature might simplify this if registration happens only at startup). Store `ToolDefinition`, `ResourceDefinition`, `PromptDefinition`, and the corresponding ZIO-wrapped handler function.
*   **`add*` Methods:** Receive definitions and handler functions (likely from the metaprogramming step) and store them. Handle duplicate checking based on settings.
*   **`get*` Methods:** Retrieve definitions or handlers by name/URI.
*   **`list*` Methods:** Return lists of all registered definitions.
*   **Core Logic Methods:**
    *   `ToolManager.callTool(name, args, context)`: Find the tool, validate args against its schema (using `zio-json` or derived structure), inject context, execute the handler `ZIO`, handle errors, and potentially perform basic result conversion (though detailed conversion to `McpSchema.Content` might happen later).
    *   `ResourceManager.readResource(uri, context)`: Check concrete resources first. If no match, check templates. If a template matches, execute its handler `ZIO` with extracted parameters, inject context, handle errors. Return `String | Array[Byte]`.
    *   `PromptManager.renderPrompt(name, args, context)`: Find the prompt, validate required args, execute the handler `ZIO` with arguments, inject context, handle errors. Return `List[Message]`.

## 7. `FastMCPScala` Server Class

This is the main user-facing class.

*   **Initialization:** Takes `name`, `version`, and `settings`. Initializes managers.
*   **Decorator Methods (`tool`, `resource`, `prompt`):** These are the primary API. They *trigger the metaprogramming logic* described in section 5. They don't perform registration directly but rely on macros/reflection to do so during compilation or server setup.
*   **MCP Handler Implementations (`listTools`, `callTool`, etc.):**
    *   These methods are *not* directly called by the user but are registered with the underlying Java MCP server instance.
    *   They delegate calls to the corresponding Manager methods (`toolManager.listToolDefinitions`, `resourceManager.listResourceDefinitions`, etc.).
    *   They perform necessary type conversions from Scala types (e.g., `ToolDefinition`) to Java MCP SDK types (e.g., `McpSchema.Tool`) using the `toJava` helpers or implicit conversions.
    *   They execute the ZIO effects returned by the managers using `Runtime.default.unsafe.run(...).getOrThrowFiberFailure()` to integrate with the synchronous callback structure expected by `McpSyncServer`'s handlers (or adapt appropriately if using `McpAsyncServer`). Error handling within the ZIO effect should translate to appropriate MCP errors if possible.
*   **`setupServer(McpServerTransportProvider)`:**
    *   Takes a Java `McpServerTransportProvider`.
    *   Instantiates and configures the Java `McpSyncServer` (or `McpAsyncServer`).
    *   Defines the `ServerCapabilities` based on whether tools, resources, or prompts have been registered with the managers.
    *   **Crucially:** Creates the necessary Java `BiFunction` handlers (like `javaToolHandler`, `javaPromptHandler` shown previously) for each registered tool/prompt/resource. These BiFunctions bridge the Java SDK's expected handler signature with the Scala/ZIO logic by:
        1.  Accepting the Java `McpSyncServerExchange` (or Async) and `Map[String, Object]` / `McpSchema.GetPromptRequest` / `McpSchema.ReadResourceRequest`.
        2.  Converting Java arguments to Scala types.
        3.  Creating the Scala `Context`.
        4.  Calling the appropriate manager method (`callTool`, `readResource`, `renderPrompt`).
        5.  Executing the resulting ZIO effect synchronously using `Runtime.default.unsafe.run`.
        6.  Converting the Scala result back to the required Java MCP SDK type (e.g., `McpSchema.CallToolResult`, `McpSchema.ReadResourceResult`, `McpSchema.GetPromptResult`).
        7.  Handling errors from the ZIO effect and converting them potentially into Java exceptions or MCP error responses if the BiFunction signature allows.
    *   Registers these `BiFunction` handlers with the Java `McpServer` builder using the `.tool()`, `.resource()`, `.prompt()` methods *of the Java builder*.
    *   Builds and stores the `underlyingJavaServer`.
*   **`run`, `runStdio`, `runSse`:**
    *   `run(transport)`: Selects `runStdio` or `runSse` based on the argument.
    *   `runStdio()`: Creates a Java `StdioServerTransportProvider`, calls `setupServer` with it, and then blocks the ZIO fiber indefinitely (as the Java transport handles the main loop). Log server start.
    *   `runSse()`: More complex. Would need to:
        *   Create a Java SSE transport provider (e.g., `WebFluxSseServerTransportProvider` or `HttpServletSseServerTransportProvider`).
        *   Call `setupServer` with it.
        *   Start the underlying web server (e.g., Netty via ZIO HTTP or Http4s, or potentially leverage the Spring Boot context if run within it, or even Tomcat embedded as in Java SDK tests). This requires careful integration between the ZIO runtime and the web server's lifecycle. Initial implementation might focus on `stdio`.

## 8. `Context` Class

Provide a Scala-friendly API over the Java SDK's exchange object.

*   **Fields:** Store an `Option[McpSyncServerExchange]` (or Async).
*   **Methods:**
    *   `getClientCapabilities`, `getClientInfo`: Access properties on the Java exchange.
    *   `log(level, message, loggerName)`: Delegate to `underlyingJavaServer.loggingNotification(...)`. Needs access back to the `FastMCPScala` instance or the transport provider.
    *   `reportProgress(current, total)`: Delegate to `javaExchange.get.getSession.sendProgressNotification(...)` (or similar Java SDK method - TBD based on Java SDK structure).
    *   `readResource(uri)`: Delegate to `this.server.readResource(uri)` (requires reference back to `FastMCPScala` instance). Wrap in `ZIO.fromFutureJava` or `ZIO.attemptBlocking` if the underlying Java call is async/blocking.
    *   Provide convenience methods `debug`, `info`, `warning`, `error`.

## 9. ZIO Integration Details

*   **Effect Wrapper:** All user-defined tool/resource/prompt functions should ideally return `ZIO[R, E, A]`. If they are synchronous, the metaprogramming layer should wrap them using `ZIO.attempt`.
*   **Error Handling:** Use `ZIO.fail` for expected errors (e.g., ToolNotFound, ResourceNotFound). Use `catchAll` or `mapError` within handler implementations to convert Throwables into appropriate MCP error responses or log them.
*   **Interop:** When calling Java SDK methods that return `Mono` or `CompletableFuture`, use `ZIO.fromFutureJava` or `ZIO.fromMono`. When providing handlers *to* the Java SDK (like the `BiFunction`s), use `Runtime.default.unsafe.run(...).getOrThrowFiberFailure()` to execute the ZIO effect and get the result synchronously, as required by the `McpSyncServer` handlers. If integrating with `McpAsyncServer`, the ZIO effect could potentially be converted directly to a `Mono`.

## 10. JSON Handling (`zio-json`)

*   **Codecs:** Define `given JsonCodec[...]` for all core Scala types (`Message`, `Content` subtypes, `Role`, `PromptArgument`, etc.).
*   **Schema Generation:** Explore using `zio-json`'s schema capabilities (`zio.json.ast.Json.Schema`) to generate the `inputSchema` for `ToolDefinition`. This might require custom derivation logic or mapping ZIO Schema types to the structure expected by `McpSchema.JsonSchema`.
*   **Parsing/Serialization:** Use `zio-json` for parsing incoming arguments (especially if they are JSON strings) and serializing complex return types from tools/resources if they aren't plain `String` or `Array[Byte]`.

## 11. Testing Strategy

*   **Unit Tests:** Test Managers, Type conversions (`toJava`), Context methods, Macro/Reflection logic (if possible).
*   **Integration Tests:** Create simple `FastMCPScala` servers with various tools/resources/prompts. Use the `java-sdk`'s `MockMcpClientTransport` and `MockMcpServerTransportProvider` (or implement Scala equivalents) to simulate client-server interaction *without* actual transport layers. Test handler invocation, argument passing, context injection, and result conversion.
*   **End-to-End Tests:** Use `StdioClientTransport` from the `java-sdk` to connect to a `FastMCPScala` server running with `runStdio()` and verify actual protocol communication.

## 12. Future Work

*   Full Async Integration (`McpAsyncServer`).
*   SSE Transport (`runSse`).
*   Support for advanced MCP features (Subscriptions, Roots server-side handling, etc.).
*   Refined JSON Schema generation/handling.
*   Comprehensive error mapping between ZIO failures and MCP errors.
*   Investigate using ZIO HTTP or http4s for the SSE server instead of relying solely on Java SDK transports.

This guide provides a detailed roadmap. The agent should proceed step-by-step, focusing on core type definitions, manager logic, metaprogramming for decorators, and finally integrating with the Java SDK server and transport layers, using ZIO throughout for effect management.
