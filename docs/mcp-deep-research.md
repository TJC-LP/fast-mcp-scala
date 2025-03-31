# Anthropic MCP Java SDK and the FastMCP Scala 3 Design

## Overview of Anthropic’s Model Context Protocol (MCP) and Java SDK

Anthropic’s **Model Context Protocol (MCP)** is an open standard for connecting AI models with external tools, data, and context in a standardized way ([Introducing the Model Context Protocol \ Anthropic](https://www.anthropic.com/news/model-context-protocol#:~:text=Today%2C%20we%27re%20open,produce%20better%2C%20more%20relevant%20responses)) ([Introducing the Model Context Protocol \ Anthropic](https://www.anthropic.com/news/model-context-protocol#:~:text=The%20Model%20Context%20Protocol%20is,that%20connect%20to%20these%20servers)). In an MCP system, an **MCP Server** provides *context* to an AI application (the client) in the form of **tools**, **resources**, and **prompts**, while an **MCP Client** (running alongside an AI model like Claude) connects to the server to use those capabilities ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20MCP%20Server%20is%20a,of%20the%20protocol%2C%20responsible%20for)). The Java SDK for MCP is an official implementation maintained with the Spring AI team, enabling Java applications to easily implement MCP servers or clients ([GitHub - modelcontextprotocol/java-sdk: The official Java SDK for Model Context Protocol servers and clients. Maintained in collaboration with Spring AI](https://github.com/modelcontextprotocol/java-sdk#:~:text=A%20set%20of%20projects%20that,synchronous%20and%20asynchronous%20communication%20patterns)) ([GitHub - modelcontextprotocol/java-sdk: The official Java SDK for Model Context Protocol servers and clients. Maintained in collaboration with Spring AI](https://github.com/modelcontextprotocol/java-sdk#:~:text=Spring%20AI%20MCP%20extends%20the,MCP%20support%20using%20Spring%20Initializer)).

**Structure and capabilities:** The MCP Java SDK follows a layered architecture with clear separation of concerns ([Overview - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-overview#:~:text=The%20SDK%20follows%20a%20layered,with%20clear%20separation%20of%20concerns)):

- **MCP Server (server-side):** Handles the server end of the protocol. It exposes tools for execution, manages resources (data accessible via URIs), provides prompt templates, and negotiates capabilities with clients ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20MCP%20Server%20is%20a,of%20the%20protocol%2C%20responsible%20for)). The server can handle multiple concurrent clients and supports both synchronous (blocking) and asynchronous modes ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20server%20supports%20both%20synchronous,integration%20in%20different%20application%20contexts)). It’s responsible for registering tools/resources/prompts and executing them on request.
- **MCP Client (client-side):** Manages the connection to an MCP server from the AI application side. It can discover available tools, call them, fetch resources, and retrieve prompts. (Our focus is on the server side in this discussion.)
- **Session & Transport Layers:** The SDK includes a session layer (`McpSession`) for managing state and a transport layer (`McpTransport`) for communication. It supports multiple transports, including standard I/O streams (for local processes) and HTTP Server-Sent Events (SSE) for networked client-server communication ([Overview - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-overview#:~:text=,based%20HTTP%20streaming)). This means an MCP server can run as a separate process communicating via pipes, or as a web service streaming JSON-RPC messages over HTTP SSE, etc.

**MCP SDK features:** The Java SDK implements the full MCP specification. Key capabilities include:

- **Tool support:** Defining and exposing tools (functions the model can call) with automatic discovery, execution on demand, and change notifications if the tool list updates ([Overview - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-overview#:~:text=,Sampling%20support%20for%20AI%20model)).
- **Resource support:** Exposing context **resources** identified by URIs, with the ability to manage hierarchical data (roots, folders) and serve content to the client ([Overview - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-overview#:~:text=negotiation%20spec.modelcontextprotocol.io%20%20,Sampling%20support%20for%20AI%20model)).
- **Prompt support:** Providing predefined prompt templates that the client can list and fetch to guide model interactions ([Overview - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-overview#:~:text=notifications%20,Sampling%20support%20for%20AI%20model)).
- **Sampling & model integration:** Support for model **sampling** capabilities (allowing the server to request the client model to generate text) ([Overview - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-overview#:~:text=,support%20for%20AI%20model%20interactions)), though this is beyond basic tools/resources.
- **Logging and notifications:** The server can send logging messages or other notifications to the client in a structured way (useful for debugging or progress updates) ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=,Providing%20structured%20logging%20and%20notifications)).

Developers use the Java SDK by configuring an `McpServer` instance, enabling the desired capabilities (tools, resources, prompts, etc.), and then registering their own tools, resources, and prompts with the server. The SDK handles the protocol details (JSON-RPC message framing, request/response handling, etc.) so the developer can focus on the functionality of their tools and context data.

## Defining Tools in the MCP Java SDK

**What are MCP tools?** In MCP, a *tool* is an action or function that the AI assistant can invoke through the protocol. Tools enable the model to perform operations like calculations, database queries, API calls, etc., as instructed by the user or on its own. Each tool is defined by a **name**, a **description**, and an **input parameter schema** that tells the client (and ultimately the AI model) what arguments the tool expects ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=Each%20tool%20is%20defined%20with,the%20following%20structure)). The tool’s logic lives on the server and produces a result when invoked.

**Tool schema:** The MCP protocol uses JSON Schema to describe each tool’s input parameters ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=Each%20tool%20is%20defined%20with,the%20following%20structure)). In the Java SDK, when defining a tool, the developer supplies a JSON Schema (as a string or object) that defines the expected arguments. For example, a simple “calculator” tool that adds two numbers might have a schema like:

```json
{
  "type": "object",
  "properties": {
    "a": { "type": "number" },
    "b": { "type": "number" },
    "operation": { "type": "string" }
  },
  "required": ["a", "b", "operation"]
}
``` 

This schema declares that the tool expects numeric inputs `a` and `b` and an operation string (perhaps `"add"` or `"multiply"`), and that all three are required. The schema is important because it is communicated to the client; the AI model can view the tool’s parameters and know how to format a call. (In essence, tools in MCP are analogous to function calls with a JSON Schema-defined signature ([Understanding the Model Context Protocol (MCP) and Building Your First Memory Server - Grizzly Peak Software](https://grizzlypeaksoftware.com/articles?id=4Tyr7iByM6tvJI1WzshwsC#:~:text=Each%20tool%20has%20a%20name%2C,the%20tools%20with%20appropriate%20arguments)).)

**Registering a tool (Java SDK):** The Java SDK provides classes to define and register tools. Typically, one would:

1. **Create a Tool definition:** using the `Tool` class, which includes the tool’s name, description, and parameter schema. For example:

   ```java
   String schema = "{ ... }";  // JSON schema as above
   Tool calcTool = new Tool("calculator", "Basic calculator", schema);
   ```

   Here `"calculator"` is the unique tool name and the description is a human-readable hint for the model or user ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=%2F%2F%20Sync%20tool%20specification%20var,)).

2. **Provide an implementation handler:** In the Java SDK, you pair the `Tool` with a function (often a lambda) that implements the tool’s logic. The SDK defines a `ToolSpecification` which encapsulates the tool definition plus its handler. For instance, using a synchronous tool spec:

   ```java
   McpServerFeatures.SyncToolSpecification calcSpec = new McpServerFeatures.SyncToolSpecification(
       calcTool,
       (exchange, arguments) -> {
           // parse arguments from the map
           double a = ((Number) arguments.get("a")).doubleValue();
           double b = ((Number) arguments.get("b")).doubleValue();
           String op = (String) arguments.get("operation");
           // perform operation
           double result = op.equals("add") ? (a + b) : (a * b);
           // return result to client (wrap in CallToolResult)
           return new CallToolResult(result, false);
       }
   );
   ```

   In this handler, the SDK passes an `arguments` map (parsed according to the schema) and an exchange context. The developer writes the logic to produce an output (here we simply add or multiply). The result is returned as a `CallToolResult`, which the SDK will serialize and send back to the client. The boolean flag (`false` in this example) might indicate whether the result is streaming or final (here it's a one-shot result).

3. **Add the tool to the server:** Finally, you register the tool with your `McpServer` instance:

   ```java
   mcpServer.addTool(calcSpec);
   ```

   This makes the tool available to clients. The server will advertise it in the tool list and be ready to execute it when called ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=%2F%2F%20Register%20tools%2C%20resources%2C%20and,addPrompt%28syncPromptSpecification)) ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=,side%20protocol%20operations)).

According to the documentation, *“The Tool specification includes a Tool definition with name, description, and parameter schema followed by a call handler that implements the tool’s logic.”* ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20Tool%20specification%20includes%20a,a%20map%20of%20tool%20arguments)). This is exactly what we did above: `Tool(...)` provides name/description/schema, and the lambda provides the logic. The Java SDK supports both synchronous and asynchronous tool handlers (the async variant would return a future/promise instead of a direct result) – but either way, the pattern is similar.

**Tool discovery and invocation:** Once registered, tools can be discovered and invoked through standardized MCP requests. The client can ask the server for the list of available tools via the `tools/list` method, which returns all tool names, descriptions, and schemas ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=,calculations%20to%20complex%20API%20interactions)). Then, when the AI model decides to use a tool, the client sends a `tools/call` request with the tool’s name and a JSON payload of arguments. The SDK will route this to the correct handler (e.g., our `calcSpec` above), take the returned result, and send it back in the `tools/call` response. This flow is entirely managed by the SDK once the tools are registered. In summary, defining a tool in the Java SDK means providing its *specification* (metadata + schema) and its *implementation*, and the SDK takes care of the rest – exposing it to the model and handling the call/response lifecycle.

## Defining Contextual Resources in the MCP Java SDK

**What are resources?** In MCP, a *resource* is a piece of data or content that the server can expose for the client/model to read (but typically not to modify) ([Resources - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/resources#:~:text=Resources%20are%20a%20core%20primitive,as%20context%20for%20LLM%20interactions)) ([Understanding the Model Context Protocol (MCP) and Building Your First Memory Server - Grizzly Peak Software](https://grizzlypeaksoftware.com/articles?id=4Tyr7iByM6tvJI1WzshwsC#:~:text=1)). Resources are analogous to read-only files or database entries in an API. They provide *context* – for example, the content of a document, the rows of a database query, the text of a log file, etc. – which the model can incorporate into its responses. Each resource is identified by a **URI** (Uniform Resource Identifier) that encodes what the resource is or where it’s located ([Resources - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/resources#:~:text=,And%20more)) ([Resources - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/resources#:~:text=Resource%20URIs)). For instance, you might have resources like `file:///home/user/report.pdf` or `db://customers/1234`. By standardizing on URIs, MCP lets the AI client refer to resources in a uniform way.

**Resource definition:** The Java SDK represents resources with a `Resource` class. A resource typically has:
- A **URI or URI template** – e.g. `"custom://resource"` or `"file:///path/to/doc.txt"`. This is the identifier the client will use to request it.
- A **name** and **description** – human-friendly metadata (the name might be a short label or identifier, and description explains the resource’s content or purpose).
- A **MIME type** – indicating the type of data (text, JSON, image, etc.) the resource contains.
- (Possibly other metadata like an `id` or a fingerprint for caching, but the core ones are above.)

For example, creating a resource in Java:

```java
Resource res = new Resource("custom://status", "StatusReport", "Current system status report", "text/plain", null);
```

In this hypothetical resource, the URI `custom://status` is a custom scheme URI that the client can request. The server might treat any request for `custom://status` by returning a certain piece of data (like a status summary text). The name `"StatusReport"` and description help identify it (these might show up in a UI or logs), and MIME type is `text/plain` meaning it’s plain text data.

**Resource handler:** Like tools, resources need a handler function on the server side. A resource handler is invoked when the client wants to **read** that resource. The Java SDK uses a specification object similar to tools. For example:

```java
McpServerFeatures.SyncResourceSpecification resSpec = new McpServerFeatures.SyncResourceSpecification(
    res,
    (exchange, request) -> {
        // implement reading the resource
        String content = generateStatusReport();  // your code to get the data
        return new ReadResourceResult(content);
    }
);
server.addResource(resSpec);
```

Here, `ReadResourceResult` is the SDK’s way to encapsulate the resource content (and possibly metadata like MIME type). The handler gets a `ReadResourceRequest` (which likely includes the URI being read, range if partial, etc.) and should return the content. In this simple case, whenever `custom://status` is requested, the handler calls `generateStatusReport()` to produce a text and returns it.

According to the docs, *“The resource specification [is] comprised of resource definition and [a] read handler. The resource definition [includes] name, description, and MIME type.”* ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20resource%20specification%20comprised%20of,server%20can%20interact%20with%20the)) (and of course the URI). The Java example above shows the **server registering the resource via** `server.addResource(resSpec)` ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=var%20syncResourceSpecification%20%3D%20new%20McpServerFeatures,)).

**Resource usage:** How does the client/model use resources? The MCP client can discover available resources indirectly via **roots** and listings. The server can advertise **root URIs** which are entry points or directories of resources (for example, a root could be `file:///documents/` meaning “I have a file system directory you can browse”). Clients might call a `resources/list` or similar to get sub-resources under a root, or simply know specific URIs to request. Ultimately, when a resource is needed, the client will send a `resource/read` request with the URI, and the server will invoke the corresponding handler and return the data.

One important aspect: resources in MCP are generally **user-controlled** context ([Resources - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/resources#:~:text=Resources%20are%20designed%20to%20be,For%20example)). This means the AI model typically cannot fetch resources on its own without user action or permission (unlike tools, which are model-invoked). For example, in Claude’s implementation, the user has to approve or select a resource for the model to actually see it ([Resources - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/resources#:~:text=Resources%20are%20designed%20to%20be,For%20example)). This is to prevent the AI from reading arbitrary data without oversight. In practice, the server just serves the data, and it’s up to the client how to gate access. When designing an MCP server, you expose whatever resources might be relevant (ensuring you have permission to serve them), and assume the client will handle the policy of usage.

**Dynamic resource URIs:** The MCP spec allows dynamic or parameterized URIs. For example, a resource URI might contain a parameter like `user://{id}/profile` to fetch different user profiles ([Understanding the Model Context Protocol (MCP) and Building Your First Memory Server - Grizzly Peak Software](https://grizzlypeaksoftware.com/articles?id=4Tyr7iByM6tvJI1WzshwsC#:~:text=)). In the Java SDK, one might handle this by registering a resource with a URI pattern or using the handler’s request info to parse the incoming URI. For instance, if you want to serve any file under `/home/user/docs`, you might register a resource with a base URI `file:///home/user/docs/` and in the handler, use the requested URI path to locate the exact file. The SDK doesn’t explicitly show a wildcard mechanism in the simple examples, but it’s likely the handler gets the full URI requested (`ReadResourceRequest` probably contains the URI string). So you can implement logic to handle a *family* of URIs in one handler if needed (e.g., parse the path after `file://...`). The **roots** mechanism in MCP helps the client understand what top-level URIs are available to it ([Core architecture - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/architecture#:~:text=MCP%20follows%20a%20client,where)) ([Core architecture - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/architecture#:~:text=Protocol%20layer)) (for example, the server might advertise `file:///home/user/docs/` as a root, then the client can ask for directory listings or specific files under that root).

In summary, the Java SDK makes resource exposure straightforward: define a `Resource` with URI and metadata, provide a function to return the content, and register it. This allows your AI assistant to retrieve rich context (documents, data, etc.) as needed to augment its knowledge beyond its built-in training data.

## Defining Prompts in the MCP Java SDK

**What are prompts?** *Prompts*, in MCP terms, are reusable prompt templates or predefined conversational contexts that the server can provide to the client ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=Create%20reusable%20prompt%20templates%20and,workflows)). Think of them as canned conversation starters or query templates that help guide the model’s behavior for specific tasks. For example, a prompt could be "Code Review Assistant" which, given a programming language and code snippet, produces a standardized prompt for the model to analyze the code. Prompts are intended to be **user-controlled** – the user (or UI) chooses to insert a prompt into the conversation to achieve a certain effect ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=Prompts%20are%20designed%20to%20be,explicitly%20select%20them%20for%20use)).

**Prompt definition:** Each prompt has a few key properties:

- A **name** (identifier) – e.g., `"greeting"` or `"analyze-code"`.
- A **description** – explains what the prompt is for, e.g., `"Analyze code for potential improvements"`.
- An **arguments list** – zero or more parameters that the prompt can accept ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=,Whether%20argument%20is%20required)). Each argument can have its own name, description, and a flag if it’s required. Unlike tools, these arguments do not necessarily have explicit types in the protocol (they’re usually treated as text to substitute into the template or as options for how the prompt is generated).
- (The actual prompt content or template is not stored as a single string in the definition; rather, it’s generated by the server when requested, using the handler function. This allows prompts to incorporate dynamic content like resource data or to format the prompt in a sequence of messages.)

In the Java SDK, a prompt can be defined via the `Prompt` class. For example:

```java
Prompt prompt = new Prompt(
    "greeting",
    "Friendly greeting message",
    List.of(new PromptArgument("name", "The name of the person to greet", true))
);
```

This defines a prompt called `"greeting"` with one argument `"name"` (required) and a description. The idea is that the client can ask for this prompt, supplying a value for `"name"`.

**Prompt handler:** Similar to tools/resources, we need to provide a handler for what happens when the client requests the prompt. In the SDK, this might look like:

```java
McpServerFeatures.SyncPromptSpecification promptSpec = new McpServerFeatures.SyncPromptSpecification(
    prompt,
    (exchange, request) -> {
        String nameVal = request.getArguments().get("name");
        // Construct the prompt output
        String userMsgText = "Hello, " + nameVal + "! How can I assist you today?";
        List<Message> messages = List.of(
            Message.user(userMsgText)
        );
        // The description in the result can be dynamic or same as prompt description
        return new GetPromptResult("Greeting for " + nameVal, messages);
    }
);
server.addPrompt(promptSpec);
```

In this hypothetical code, when the client calls `prompts/get` for `"greeting"` with `{"name": "Alice"}`, the handler creates a user message `"Hello, Alice! How can I assist you today?"` and returns it inside a `GetPromptResult`. The result contains a description (which could simply echo or augment the prompt’s purpose) and a list of **messages**. Each message in MCP has a role (user/assistant/system) and content. Here we created a user-role message with some text content. The client, upon receiving this, would typically inject these messages into the conversation with the model. (In this simple case, it’s just one user message; more complex prompts could include system instructions or even an assistant placeholder response.)

From the spec: *“Each prompt is defined with: name, description, and an optional list of arguments”* ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=,Whether%20argument%20is%20required)). The Java SDK’s `PromptArgument` captures each argument’s name/description/required. The **prompts are discovered** by the client via a `prompts/list` request, which returns all prompt names, descriptions, and their argument specifications ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=%2F%2F%20Response%20,language)). Then, to use a prompt, the client calls `prompts/get` with the chosen prompt’s name and a map of argument values ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=To%20use%20a%20prompt%2C%20clients,request)). The server’s handler (like above) generates the actual prompt content and returns it. The client typically then *feeds those messages to the model* as part of the conversation.

**Example usage flow:** Suppose our server had the `"greeting"` prompt registered. A user in the chat UI might select “Greeting” from a list of prompts and enter `name = "Alice"`. The MCP client (Claude or another interface) sends to the server:

```json
{ "method": "prompts/get", "params": { "name": "greeting", "arguments": { "name": "Alice" } } }
```

The server handles this via our promptSpec, and returns something like:

```json
{
  "description": "Greeting for Alice",
  "messages": [
    {
      "role": "user",
      "content": { "type": "text", "text": "Hello, Alice! How can I assist you today?" }
    }
  ]
}
```

(Behind the scenes, the `Message.user(text)` we used likely produces the JSON content with `role: "user", type: "text"` etc.) The client then inserts that user message into the chat as if the user had asked it, prompting the model to respond cheerfully.

Prompts can be more sophisticated: they might include multiple messages (system instructions, user query template, etc.), or even embed resources. The MCP spec allows prompt messages that have `type: "resource"` where the content is fetched from a URI ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=Embedded%20resource%20context)) ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=,text%2Fplain)). For example, a prompt could include a recent log file as a context by referencing a `logs://...` resource in one message, and a piece of code from `file://...` in another message ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=,text%2Fplain)) ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=,n%20%20%20%20pass)). The prompt handler in those cases would likely call back into the server’s resource system to get the content and then format the message appropriately. In any case, the **Java SDK gives you the tools to assemble whatever sequence of messages** you need in the prompt handler and return them.

To register a prompt in the Java SDK, you simply use `mcpServer.addPrompt(promptSpec)` after creating the spec with your `Prompt` and handler ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=new%20Prompt%28,)) ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20prompt%20definition%20includes%20name,instance)). After that, the prompt is available for clients to list and use. Prompts are a powerful way to guide the model’s behavior in a controlled fashion, and the SDK abstracts the details of delivering those prompt messages to the client.

## Model Context and Tool Invocation Flows in MCP

Now that we’ve covered **tools, resources, and prompts** in isolation, it’s important to understand how they work together in an MCP session – i.e., the typical flow of information between the AI model (client) and the MCP server. Below is an outline of key interactions and flows in the context of **model usage of tools and context**:

- **Connection and Capability Negotiation:** When an MCP client connects to a server, they first establish a session (over the chosen transport) and negotiate capabilities. For example, the server announces which features it has enabled (tools, resources, prompts, logging, etc.) and the protocol version ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=McpSyncServer%20syncServer%20%3D%20McpServer.sync%28transportProvider%29%20.serverInfo%28%22my,build)). The client likewise shares what it supports (e.g. maybe the client can or cannot handle certain content types or sampling requests). This ensures both sides know how to communicate. In code, when we built the server we enabled capabilities via `ServerCapabilities.builder().tools(true).resources(true)...` etc. ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=McpSyncServer%20syncServer%20%3D%20McpServer.sync%28transportProvider%29%20.serverInfo%28%22my,build)). The client will do a compatibility check. Once agreed, the session is ready.

- **Tool Discovery:** The client can ask for the list of available tools by sending a `tools/list` request. The MCP server will respond with a list of tool definitions (each including the name, description, and JSON schema of parameters) ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=,calculations%20to%20complex%20API%20interactions)). For example, it might return a JSON like:

  ```json
  { "tools": [
      { "name": "calculator", "description": "Basic calculator", 
        "inputSchema": { "type":"object", "properties":{...} } },
      { "name": "weather_lookup", "description": "Get weather info for a city", 
        "inputSchema": { ... } },
      ... 
  ] }
  ```

  The AI model, through the client, now knows what actions it *could* perform. Importantly, this list is usually fed into the model’s context (for instance, Claude might get a system message enumerating the tools and how to call them). The model won’t see the raw JSON schema necessarily, but the client may translate it into a natural language description. The **model’s prompting is set up such that it can decide to call these tools** by name when needed.

- **Tool Invocation (model-initiated):** During a conversation, if the model decides it should use a tool (based on its prompt or user request), it will output a special action (in the client’s interaction, this might be a structured data indicating “call this tool with these arguments”). The MCP client then translates that into a `tools/call` request to the server. For example, if the model wants to add `2 + 3` using our calculator tool, the client would send:

  ```json
  { "method": "tools/call", "params": { "name": "calculator", "arguments": { "a": 2, "b": 3, "operation": "add" } } }
  ```

  The server receives this, looks up the `"calculator"` tool, and invokes our registered handler with `a=2, b=3, operation="add"`. The handler computes the result (5) and returns it. The server then sends back a response to `tools/call` with the result, typically formatted as content for the model. Often, the result is put into a standardized format. In MCP (similar to OpenAI function calling), the result might be encapsulated in a *tool response message* that the model then receives. For instance, the client might feed the model something like: *“(Tool response: 5)”* or simply provide the raw number depending on how the client orchestrates the conversation. The important point is that the tool call is synchronous from the model’s perspective – it asks, and it gets a result to continue the conversation. The Java SDK handles the entire cycle from `tools/call` request to calling your Java function and back.

- **Resource Selection and Retrieval:** If the conversation requires accessing a resource (say the user requests *“Open the file report.txt”*), the model itself might not spontaneously do this (since resource access is user-controlled), but the client application can facilitate it. For example, the client UI might list available resources or have the user pick a file. Suppose the user selects a resource URI `file:///home/user/docs/report.txt` to show to the AI. The client would then send a `resources/read` request to the server for that URI. The server finds the matching resource handler (if one was registered for `file://` URIs or specifically for that file) and returns the content (e.g., the text of the file) in the response. The client can then provide that content to the model, usually by inserting it into the conversation as a message of type "resource" or as part of the user’s prompt. (In Claude’s case, it might appear as a separate message with the file’s text, marked as coming from a resource.) This allows the model to **imbibe that content as context** and proceed to answer questions or perform tasks with it. Resources often represent large context that wouldn’t fit in a prompt otherwise, so MCP streams it as needed. The server can also notify the client of changes in available resources – for example, if a new file becomes available or data updates, it can send a `roots/listChanged` notification (the SDK supports resource list change notifications) ([Overview - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-overview#:~:text=,Sampling%20support%20for%20AI%20model)), prompting the client to refresh its view.

- **Prompt Usage:** If the user or developer wants to use a pre-defined prompt template, the flow is: client calls `prompts/list` to get all prompt names and descriptions ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=%2F%2F%20Response%20,language)) (perhaps at startup or when populating a menu of prompts). When the user chooses one (possibly filling in some arguments), the client sends `prompts/get` with the prompt name and args ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=To%20use%20a%20prompt%2C%20clients,request)). The server’s prompt handler generates the sequence of messages (which could include static text, dynamic data, or resource references) and returns them ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=%2F%2F%20Response%20%7B%20description%3A%20,following%20Python%20code%20for%20potential)). The client then injects those messages into the model’s conversation. Essentially, this saves the user from manually copying a large template; it’s done programmatically. After the prompt is inserted, the conversation continues – often the next step is the model responding to that prompt.

- **Overall context management:** Throughout the above, the **MCP session maintains context** in the sense that the server might hold onto certain state (though the protocol itself is mostly stateless request/response except for subscription features). For example, if the server had a tool that maintains memory, it might keep data in memory between calls (like the “Memory Server” example that stores notes). The model’s context (the conversation) is managed on the client side, but enriched by these interactions. The important thing is that *MCP provides a structured way for an AI model to interact with external context and actions*. The flows are all mediated by JSON-RPC calls: `tools/list`, `tools/call`, `resources/read`, `prompts/list`, `prompts/get`, etc., rather than custom ad-hoc APIs ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=,calculations%20to%20complex%20API%20interactions)) ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=%2F%2F%20Response%20,language)). This uniform interface is why MCP is described as a kind of “USB-C for AI applications” – one standard plug for many kinds of extensions ([Model Context Protocol - GitHub](https://github.com/modelcontextprotocol#:~:text=The%20Model%20Context%20Protocol%20,external%20data%20sources%20and%20tools)).

- **JSON schema and argument handling:** The use of JSON Schema for tool inputs means the client can validate or format the model’s tool call requests. For instance, if the model tries to call the calculator with a string where a number is expected, the client or server can catch that. The Java SDK’s handlers receive arguments already parsed into a Java Map according to the schema, so developers can assume the types (after a cast) are correct as per schema ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20Tool%20specification%20includes%20a,a%20map%20of%20tool%20arguments)). The SDK likely uses the schema to validate the incoming arguments automatically. However, the Java SDK does not, by itself, generate these schemas; it relies on the developer to provide them when defining the tool. In other languages (Python, TS), there are libraries to generate or enforce schemas (e.g. using Pydantic or Zod), but in Java one typically writes the JSON schema or uses a helper to build it. We will address this manual step when proposing the Scala solution.

In summary, the MCP Java SDK provides a robust framework for these flows: you register tools, resources, and prompts; the SDK handles listing them to the client and invoking the correct handlers on requests. The **model’s context** is enriched by resources and guided by prompts, and **tool invocations** let the model perform actions – all through a secure, standardized exchange. The developer’s job is to specify what tools/resources/prompts exist and how they behave; the SDK and client handle the rest.

## Designing **FastMCP**: A Scala 3 Annotation-Based MCP Library

Given the structure of the Java SDK, we can envision a higher-level library in Scala 3 – tentatively called **FastMCP** – that wraps and builds on the Java SDK to simplify the developer experience. The goal of FastMCP is to let developers define tools, prompts, and resources in a **declarative, concise way** using Scala’s modern features (especially compile-time reflection via Scala 3 macros and annotations), rather than writing boilerplate code and JSON schemas by hand. Below, we outline the design and features of this proposed Scala 3 library:

### Goals and Design Approach of FastMCP

- **Annotation-driven definitions:** Allow developers to declare MCP tools, prompts, and resources using *only annotations on Scala classes or methods*, instead of imperative registration calls. This is inspired by frameworks like Python’s FastAPI (where you use decorators to define API endpoints) or Spring Boot (with annotations for REST controllers). In Scala, we can create annotations like `@Tool`, `@Prompt`, and `@Resource` to mark functions that should be exposed via MCP.

- **Automatic JSON Schema generation:** Eliminate the need for developers to manually write JSON schema strings for tool parameters. FastMCP will use **Scala 3 macros** and **compile-time reflection** to infer a JSON Schema from the Scala function signature (parameter names and types). This ensures consistency (the schema will always match the code) and saves time. For example, if a Scala tool function takes an `Int` and a `String`, the library can auto-generate a JSON schema with those fields as `"type": "integer"` and `"type": "string"` respectively.

- **Tool/Prompt metadata from code:** Use the information in the code and annotations to populate the tool name, descriptions, prompt arguments, etc. This means fewer places to update when something changes. We might allow optional override of names or descriptions in the annotation parameters, but default to using the function name or Scala docs if not provided.

- **Compile-time registration:** Wherever possible, perform work at compile time to reduce runtime overhead. Scala 3 macros can generate the necessary code to register tools and prompts with the underlying Java SDK. This not only improves performance (no reflection needed at runtime, which is also helpful for Scala Native compatibility), but also catches errors early (e.g., unsupported parameter types could be flagged at compile time).

- **Minimal runtime boilerplate:** The library should make the process of spinning up an MCP server almost trivial. Ideally, the developer writes their annotated methods and then just calls something like `FastMCPServer.start()` to launch. FastMCP will scan (or have pre-registered via macros) all the annotated definitions and register them with a new `McpServer` internally. The developer shouldn’t have to manually call `addTool` or `addPrompt` – it happens behind the scenes. This *“convention over configuration”* approach improves ergonomics.

- **Modular design:** FastMCP itself will focus on the abstraction layer and be usable on both the JVM and Scala Native. By leveraging the Java SDK (which is JVM-based) for actual protocol handling, FastMCP on Scala Native could instead target the MCP spec directly or use a lightweight client if needed. However, since Scala Native might not directly use the Java SDK, one approach is to have FastMCP core generate the data structures (tools, schemas, etc.), and separate runtime backends: one that calls into the Java SDK on JVM, and another that could handle basic JSON-RPC on native. Initially, focusing on JVM (which can use the Java SDK) is fine, but designing with portability in mind means avoiding heavy Java-reflection or other JVM-only tricks.

- **Extensibility:** The library should allow further customization. For example, if a developer wants to override the auto-generated JSON schema or provide custom serialization, there should be hooks (perhaps annotation parameters or using custom types with known schema mappers). Also, while FastMCP mainly addresses the server side (defining tools/prompts/resources), it could be extended to help with client side in Scala as well (though most clients will be AI platforms like Claude, not user-written).

### Defining Tools with Scala 3 Annotations

In FastMCP, defining a tool could be as simple as writing a Scala function with a `@Tool` annotation. For example:

```scala
import ai.fastmcp.api._  // hypothetical import for annotations

object MathTools {
  @Tool(name = "calculate_sum", description = "Add two numbers together")
  def calculateSum(a: Int, b: Int): Int = {
    a + b
  }
}
```

In this example, the developer created a function `calculateSum` that takes two integers and returns an integer. By annotating it with `@Tool`, we indicate this function should be exposed as an MCP tool. The annotation carries metadata: we explicitly set the tool’s name to `"calculate_sum"` (perhaps we want snake_case for the MCP interface) and a description. If the name or description were not provided, the library could default to using the function name and maybe a scaladoc comment as description.

**What happens under the hood:** The Scala macro behind `@Tool` will intercept this during compilation. It will:

- Read the function signature: name `calculateSum`, params `(a: Int, b: Int)`, return type `Int`.
- Generate a JSON Schema for the parameters:
    - It knows `a` and `b` are of type `Int`. We map Scala/Java `Int` to JSON Schema `"type": "integer"` (since it's an integral number). Both are non-optional primitives, so they will be *required* properties.
    - It will produce something akin to:
      ```json
      {
        "type": "object",
        "properties": {
          "a": { "type": "integer" },
          "b": { "type": "integer" }
        },
        "required": ["a", "b"]
      }
      ```
      This schema is essentially generated code (likely as a string or a JSON AST object) embedded into the program by the macro.
- Create a Tool definition: using the Java SDK’s `Tool` class or a Scala equivalent. For instance, the macro could emit code equivalent to:
  ```scala
  private val _calcSumTool = new Tool("calculate_sum", "Add two numbers together", <schemaJsonString>)
  ``` 
  Now we have the tool’s metadata ready.
- Wrap the function into a handler: The macro can generate a little adapter function that takes the generic MCP call context and arguments (just like we manually wrote the lambda in Java). Something like:
  ```scala
  val _calcSumHandler: McpServerFeatures.SyncToolHandler = { (exchange, args) =>
      val aVal = args.get("a").asInstanceOf[Int]    // macro knows expected type
      val bVal = args.get("b").asInstanceOf[Int]
      val result: Int = calculateSum(aVal, bVal)    // call the original Scala function
      new CallToolResult(result, false)            // wrap result (macro could decide how to wrap Int -> maybe as text content)
  }
  ```
  There’s a bit to unpack: converting `args` (likely a `java.util.Map` or similar from the Java SDK) into Scala types and calling the function. The macro, having the static types, can insert the appropriate casts or even use safer extraction (we could integrate with Jackson or Circe to decode `args` to a Scala case class, but that’s likely overkill; simple types are fine). We then call the function `calculateSum` which is in the same scope, and get the result. We wrap it in `CallToolResult`. The `CallToolResult` in Java expects the result content. If the result is a primitive or string, the Java SDK might automatically turn it into a text response ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=server.setRequestHandler%28CallToolRequestSchema%2C%20async%20%28request%29%20%3D,)). (If it needed to be structured, we could encode it as JSON string. For now, assume returning an `Int` becomes a text content `"5"` for example, or we could convert to string ourselves.)
- Register the tool: Finally, the macro needs to ensure this tool is registered with the MCP server. We have a few options:
    - The macro could generate a global registration table that collects all such tools, and then at runtime FastMCP goes through it to add them to the server.
    - Or, if we have an object extending a base trait, the macro could directly add to the server in that object’s initialization. For example, if `MathTools` extended a `FastMCPServer`, the macro might have access to a server instance and call something like `server.addTool(new SyncToolSpecification(...))`. However, directly calling at construction time could be tricky if the server isn’t created yet.

A likely design is that FastMCP provides a *bootstrap function* that scans a given set of objects for annotated members. Since Scala 3 macros can work with known static structures, we can do something like:

```scala
// in some main or server builder code
val server = FastMCPServer.buildFrom(MathTools, OtherTools, MyPrompts)
server.start()
```

Where `FastMCPServer.buildFrom(objects...)` is a macro that reads those objects’ annotated methods and performs the registration. This way, we explicitly tell the macro which objects to process (avoiding complex classpath scanning). The macro then generates code that does:

```scala
val mcpServer = McpServer.sync(defaultTransport).serverInfo(...).capabilities(...).build()
mcpServer.addTool(new SyncToolSpecification(_calcSumTool, _calcSumHandler))
// ... and similarly for other tools/prompts in the passed objects
return new FastMCPServer(mcpServer)
```

Essentially, `buildFrom` could expand into a series of `addTool` calls for each annotated method it finds. The end result is an `FastMCPServer` (a thin wrapper containing the Java `McpServer`) that is fully configured. Then `server.start()` might just initiate the transport listening (for SSE or attach to stdio).

**JSON schema generation details:** The macro must map Scala types to JSON Schema types:
- Basic types: `Int`, `Long` -> `"integer"`; `Double`, `Float` -> `"number"` (since they can be fractional); `Boolean` -> `"boolean"`; `String` -> `"string"`.
- Collections: e.g. `Seq[T]` or `List[T]` -> `"type": "array"` with an `"items"` schema derived from `T`. So `List[String]` becomes type array of strings.
- Option[T]: optional parameters. If a parameter is of type `Option[T]`, we treat the underlying type `T` as the schema for that field, but we will **not list that field as required** (because it may be None/missing). We might even include `"nullable": true` or allow `type: ["null", ...]` if following JSON Schema draft that supports nulls. But simplest is just not to include it in `"required"`.
- Case classes or complex types: It’s possible a function could take a case class as a single parameter (e.g. `def makeChart(options: ChartOptions): Image`). In such case, we could delve into the case class fields via Scala 3 Mirror (which provides compile-time reflection of case classes) and generate a nested schema. This is advanced but doable. Initially, we expect most tool parameters to be primitives or small structures. But designing the macro to handle nested case classes means FastMCP could support more complex argument structures. Each case class field would become a sub-property in the schema. This is analogous to how OpenAPI generates schemas for complex objects.
- We should also consider string enumerations or sealed traits: If a parameter is an `enum` or `sealed trait` with limited cases, we could produce a schema with an `"enum": [values]`. For example, if `operation` parameter in our calculator were an `enum Operation { Add, Multiply }`, the macro could detect that and output something like `"operation": { "type": "string", "enum": ["Add","Multiply"] }`. This is a nice feature to guide the model’s usage (it knows the only allowed operations).
- Default parameter values: If a Scala method has a default value for a parameter, we could reflect that and generate a `"default": <value>` in the schema, and possibly mark it not required (since if not provided, the server will use default). This aligns with how e.g. FastAPI treats defaulted parameters as optional.

All this logic would be encoded in the macro. The heavy lifting is done at compile time, so at runtime the server just has ready-made schemas.

**Example outcome:** For our `calculateSum` example, FastMCP would register a tool with name `"calculate_sum"`, description `"Add two numbers together"`, and the schema for `a` and `b` as described. When a client calls this tool, the generated handler calls the Scala function and returns the result. The developer did not have to manually parse any JSON or write any schema – it was derived from the type signature.

Another example: imagine a tool function in Scala:

```scala
@Tool def searchCustomers(name: String, maxResults: Int = 10): List[Customer] = { ... }
```

Where `Customer` is a case class `Customer(id: Int, name: String)`. FastMCP could generate a schema:
```json
{
  "type": "object",
  "properties": {
    "name": { "type": "string" },
    "maxResults": { "type": "integer", "default": 10 }
  },
  "required": ["name"]
}
```
(`maxResults` is not required because it has a default). For the output, a `List[Customer]` could be serialized to JSON (each Customer to an object with id and name fields). While MCP doesn’t mandate an output schema for tools (tools can return arbitrary JSON content or text), FastMCP might choose to automatically serialize case class results to JSON string content. This way, the model can receive structured results if needed. For now, focusing on input schema is enough, since that’s what MCP explicitly uses in negotiation with the model ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=Each%20tool%20is%20defined%20with,the%20following%20structure)).

### Defining Prompts with Scala 3 Annotations

Prompts in FastMCP can also be simplified. We introduce a `@Prompt` annotation to designate a method as providing a prompt template. For example:

```scala
object MyPrompts {
  @Prompt(name = "greeting", description = "Generate a friendly greeting")
  def greetingPrompt(name: String): PromptResult = {
    // Build a prompt with a user message that greets the given name
    val userText = s"Hello, $name! How can I assist you today?"
    PromptResult(
      description = s"Greeting prompt for $name",
      messages = Seq(Message.user(userText))
    )
  }
}
```

Here, `PromptResult` and `Message` are types we assume FastMCP provides to construct the output. Perhaps:
- `PromptResult(description: String, messages: Seq[Message])` is a simple case class we use internally to represent what the `prompts/get` response should contain.
- `Message` could be a sealed trait or class where we can create text messages or resource references easily. E.g., `Message.user(text: String)` creates a user-role text message; `Message.assistant(text)` an assistant message; maybe `Message.resource(uri: String, fallbackText: String)` to embed a resource.

The macro for `@Prompt` will:

- Extract the prompt’s metadata: name `"greeting"` and description.
- Determine the prompt arguments from the method parameters. In `greetingPrompt(name: String)`, it sees one parameter “name” of type String. In MCP, prompt arguments don’t carry type info (the assumption is they are text inputs by default). We will treat all prompt parameters as strings for simplicity, because ultimately they will be interpolated or used in text. We mark "name" as required (since no default and it’s not Option).
- Create a `Prompt` definition (likely using the Java SDK’s `Prompt` class) with name, description, and a list containing one `PromptArgument("name", "...", true)` ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=,Whether%20argument%20is%20required)). We might use the same description for the argument as the prompt if none provided, or allow an annotation on the parameter to specify it.
- The handler: The macro wraps the method in a handler similar to tools, but for prompts. It will call the Scala function to get a `PromptResult`. For instance:
  ```scala
  val _greetingHandler = { (exchange: McpAsyncServerExchange, req: GetPromptRequest) =>
      val nameVal = req.getArguments.get("name").asInstanceOf[String]
      val result = greetingPrompt(nameVal)
      // convert PromptResult to GetPromptResult
      new GetPromptResult(result.description, result.messages.map(_.toMcpMessage))
  }
  ```
  Here `toMcpMessage` would convert our `Message` abstraction to whatever the Java SDK expects (perhaps a `Message` object or a JSON content structure). The macro can generate that mapping if we define how `Message` translates (likely by pattern matching on a sealed trait).
- Register the prompt: via `server.addPrompt(new PromptSpecification(promptDef, _greetingHandler))` in the generated code.

With this, the developer just provides the logic for constructing the prompt’s messages, and the library does the rest.

**Example scenario:** A more complex prompt could be something like a code analysis prompt that takes a `language: String`. In Scala:

```scala
@Prompt(name="analyze_code", description="Analyze code for potential improvements")
def analyzeCodePrompt(language: String): PromptResult = {
  PromptResult(
    description = s"Analyze $language code for potential improvements",
    messages = Seq(
      Message.user(s"Please analyze the following $language code for potential improvements:"),
      Message.userResource("code://current-file")  // hypothetical: embed current file content
    )
  )
}
```

Here we used a made-up `Message.userResource("code://current-file")` to indicate we want to include a resource content (say the client knows `code://current-file` refers to whatever code the user is looking at). The macro would register a prompt "analyze_code" with one argument "language". The actual inclusion of the resource would result in the server, when handling `prompts/get`, embedding a resource reference in the messages (the Java SDK likely has a way to include a resource in a `MessageContent`).

The benefit is the developer can express in near-natural Scala code what the prompt should do, without fiddling with lower-level message construction APIs.

### Defining Resource Bindings with Scala Annotations

For resources, FastMCP can provide a `@Resource` annotation to map Scala methods to resource URIs. There are a couple of use cases:

- **Static or singular resources:** e.g., a status or config blob that doesn’t take parameters.
- **Parameterized resource families:** e.g., all files in a directory, or all records of a certain type identified by an ID in the URI.

We can support both via annotation parameters. For instance:

```scala
object MyResources {
  @Resource(uri = "myapp://status", description = "Current system status", mimeType = "text/plain")
  def getStatus(): String = {
    // generate a status report
    "All systems operational."
  }
}
```

This defines a single resource at URI `myapp://status`. The method returns a `String` (which will be the content). The macro would:

- Register a `Resource` with URI `myapp://status`, name maybe "status" (or we could use the function name or explicitly allow it in annotation), description, MIME type "text/plain".
- Create a handler that calls `getStatus()` and wraps the string into a `ReadResourceResult`.
- Add it to the server.

For dynamic resources, we might allow a URI *pattern* with placeholders. Example:

```scala
@Resource(uriPattern = "file://{path}", description = "Read a file from disk", mimeType = "text/plain")
def readFile(path: String): Array[Byte] = {
  java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))
}
```

Here, `uriPattern = "file://{path}"` indicates that any URI starting with `file://` and some path should be handled by this method, with the `{path}` part of the URI passed as the argument to the function. The macro can interpret this pattern:
- It would register perhaps a *root resource* for `file://` scheme or a generic resource handler.
- When a `resources/read` request comes in, the server (or our wrapper) would match the URI against known patterns. For `file://something`, it matches our pattern, extracts the substring for `{path}` (i.e., everything after `file://`).
- The handler then calls `readFile(path)` with that extracted path. The result is a byte array, which we wrap in `ReadResourceResult` (with the known MIME type).
- We might have to consider security (reading arbitrary files could be dangerous), but presumably the developer using this will ensure it’s safe or sandboxed.

The FastMCP library could implement simple pattern matching for one placeholder as above. For multiple parameters, maybe a URI like `db://{table}/{id}` mapping to `def readRecord(table: String, id: Int): String`. It gets complex, but possible with regex. Initially, one segment placeholder might suffice.

For example, if `readFile` as above is registered, a client’s request for `file:///etc/hosts` would trigger our handler with `path = "/etc/hosts"` and return its contents (if allowed).

**Resource schema vs code:** Unlike tools, resources don’t use JSON schema for their inputs – the “input” is the URI itself. So our macro doesn’t need to generate a schema, but it should inform the server about the resource’s existence. The Java SDK has the concept of *roots* and resource listings. We might integrate by registering a root for `file://` with description "Local file system" so that clients might know they can ask for `file://` URIs. Possibly, the `Resource` class `name` field could serve as an identifier for a root or group. For simplicity, FastMCP could automatically register a root for each unique scheme or top-level path it encounters in `uriPattern`. For `file://{path}`, root would be `file://` (or some base path if we restrict it). For `myapp://status` (no pattern), it might register `myapp://` as root with one child.

This part can be refined, but the core idea is to allow exposing data sources with minimal fuss. The developer writes a method that returns the data (string, bytes, JSON, etc.), and FastMCP handles wiring it to a URI. The annotation carries the URI info.

### Putting it Together: Initialization and Usage

Using FastMCP, a developer might structure their code as follows:

```scala
// Define all tools, prompts, resources in objects or classes with annotations
object MathTools {
  @Tool(name="calculate_sum", description="Add two numbers") 
  def calculateSum(a: Int, b: Int): Int = a + b
  @Tool(name="random_number", description="Generate a random number up to max")
  def random(max: Int): Int = scala.util.Random.nextInt(max)
}
object MyResources {
  @Resource(uri="myapp://status", description="System status report", mimeType="text/plain")
  def getStatus(): String = "All systems operational."
}
object MyPrompts {
  @Prompt(name="welcome", description="Welcome message prompt")
  def welcomePrompt(userName: String): PromptResult = {
    PromptResult(
      description = s"Welcome prompt for $userName",
      messages = Seq(
        Message.system("You are a helpful assistant."),
        Message.user(s"Hello $userName, welcome to our service!")
      )
    )
  }
}
```

After defining these, launching the MCP server could be done in a `main` method:

```scala
@main def runMcpServer(): Unit = {
  FastMCPServer
    .withInfo(name="my-server", version="1.0.0")
    .withTransports(FastMCPTransports.stdio())  // use standard I/O or other provided transports
    .build(MathTools, MyResources, MyPrompts)   // compile-time macro to gather definitions
    .startBlocking()
}
```

In this imaginary API:
- `withInfo` and `withTransports` set up basic server info and transport (perhaps FastMCP offers some predefined transport configurations).
- `.build(objects...)` is a macro that will process `MathTools`, `MyResources`, `MyPrompts` objects, find all annotated members, and register them to a new McpServer under the hood. It returns a `FastMCPServer` instance (which wraps `McpServer`).
- `.startBlocking()` might start listening (if using SSE, it might open an HTTP port; if stdio, just begin reading from stdin, etc.) and block the main thread.

Alternatively, the library might automatically choose a default transport (for example, if it detects it’s running as a Claude local server, it might go with stdio by default). But allowing configuration is good.

**Automatic discovery vs explicit listing:** In the `.build` call, we explicitly listed our objects. We could make FastMCP automatically find all objects that extend some marker trait or all methods annotated in the classpath, but that can be complicated without reflection. Being explicit is not too bad and keeps it clear. We could also allow something like:

```scala
FastMCPServer.buildAllInPackage("com.myapp.mcp")
```

where a macro tries to iterate over all known objects in that package. Scala 3 macro reflection might allow listing of symbols in a package at compile time (not sure if easily), but if not, an alternative is using a run-time classpath scan (which we want to avoid for Native). So listing them manually or grouping them is fine.

### Developer Ergonomics and Comparison

The proposed FastMCP library drastically simplifies the developer experience compared to using the raw MCP Java SDK (or similar approaches in other languages):

- **No manual schema writing:** In the Java SDK, defining a tool requires writing out a JSON schema for the parameters by hand ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=%2F%2F%20Sync%20tool%20specification%20var,)). This is error-prone and tedious – especially if the parameters change (you must remember to update the schema string). With FastMCP, the JSON schema is generated automatically from the Scala function signature. This not only saves effort, but ensures the schema always matches the code. For instance, if you change a parameter from `Int` to `Double`, the schema will update to `"type": "number"` automatically. The developer can focus on logic, not JSON syntax.

- **Single source of truth:** The annotations consolidate what would be spread across multiple places. In Java SDK, you name the tool in the Tool object, again in the schema, and write the description in two places (schema and maybe comments) – potential for inconsistency. In FastMCP, you write the name/description once in the annotation, and that populates everywhere needed. The function’s parameters are the single definition of what inputs exist.

- **Less boilerplate code:** Registering a simple tool in Java took ~10 lines of code (constructing Tool, lambda, adding to server) ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=%2F%2F%20Sync%20tool%20specification%20var,)) ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=var%20syncToolSpecification%20%3D%20new%20McpServerFeatures,)). In Scala with FastMCP, it’s literally the function itself plus one annotation line. There is no need to write code to extract arguments from a map or build result objects – the library handles those details. This conciseness makes the code more readable and maintainable. It reads like standard function definitions rather than protocol handling.

- **Type safety and IntelliSense:** Since FastMCP uses the Scala compiler to derive schemas and glue code, many errors can be caught at compile time. If you accidentally use an unsupported type as a parameter or return (for example, a complex type that the macro doesn’t know how to serialize), the macro can emit a helpful compile-time error or warning. Also, in an IDE, when writing an annotated function, you get full autocompletion and type checking for your Scala code (contrasted with writing a JSON schema in a string where a typo might only be caught at runtime). This lowers the barrier for developers to add new tools/prompts quickly.

- **Enhanced capabilities via Scala features:** Scala allows some patterns that can be leveraged. For example, one could use case classes to group parameters logically, or default arguments as mentioned. Macros can utilize these to enrich the schema (like adding `"default"` values). In Java, one might have to use a builder or extra code to indicate a default. Scala’s richer type system (including algebraic data types) can enable more expressive schemas (enums, oneOf types, etc.) without extra manual coding.

- **Comparative to other languages:** Python and TypeScript also have MCP SDKs, but those often require either writing a separate schema (TypeScript might use a library like Zod to define the schema alongside the function, which is a bit redundant) ([Understanding the Model Context Protocol (MCP) and Building Your ...](https://grizzlypeaksoftware.com/articles?id=4Tyr7iByM6tvJI1WzshwsC#:~:text=,string)), or rely on runtime type info. FastMCP’s compile-time approach is more akin to how **FastAPI** (Python) uses Python type hints to generate an OpenAPI schema automatically – except Scala’s compile-time guarantees mean we don’t even pay a runtime cost for introspection. In a sense, FastMCP would bring the convenience of something like Spring Boot’s annotation model or FastAPI’s type-driven model to the MCP world.

- **Registration and discovery:** With FastMCP, a developer doesn’t have to worry about calling the right `addTool`/`addPrompt` in the correct order or ensuring the server capabilities flags are set. The library can infer which capabilities to enable by what is present. If you have at least one `@Tool`, it knows to turn on tools support (`ServerCapabilities.tools(true)`). If you have resources, enable resources, etc. This reduces configuration steps. It “just works” based on what you annotated. This is analogous to how Spring will auto-enable certain auto-configurations if it sees certain beans.

- **Cross-platform considerations:** Because FastMCP does as much as possible at compile time, using it on Scala Native (which doesn’t support dynamic class loading or reflection well) is feasible. The generated code is plain Scala/Java calls. If the Java SDK cannot run on Scala Native, one could implement a minimal MCP protocol server in Scala for native (perhaps using the MCP spec directly, since it’s mostly JSON exchange). The annotation macros would still be valuable, as they’d produce the JSON schemas and a dispatch structure. For example, macros could generate a big pattern-match on `method` names (tools vs prompts vs resources) and call the appropriate Scala methods. This would mimic what the Java SDK does but in pure Scala. Thus, FastMCP could have an alternate backend for Scala Native. In any case, on the JVM it leverages the battle-tested Java SDK, and on Native it could fall back to a simpler implementation, all transparent to the developer.

- **Example** – *Before vs After:* Imagine a developer wants to add a new tool to their MCP server that fetches weather info. Using the Java SDK, they must:
    1. Define the JSON schema for inputs (city name, maybe units).
    2. Instantiate a `Tool("weather", "...", schema)`.
    3. Write a handler lambda that calls a weather API and formats the result.
    4. Register it with `server.addTool`.
    5. Test that the schema is correctly recognized by the model (maybe realize they forgot to mark a field required and fix it).

  With FastMCP in Scala:
  ```scala
  @Tool(name="get_weather", description="Get weather for a city")
  def getWeather(city: String): String = {
    // call some API
    val info = WeatherAPI.fetch(city)
    s"The weather in $city is ${info.temperature}°C with ${info.condition}."
  }
  ```
  That’s it. The library would infer the schema as `city` a required string. The model would know it needs to provide a city name. The returned string will be sent back as the content. If later they want to return a structured result (say a JSON with temperature and condition separately), they could change the return type to a case class `WeatherInfo(temp: Double, condition: String)` and adjust the code. FastMCP could detect that and maybe automatically serialize `WeatherInfo` to JSON text for the model. The developer doesn’t have to write that serialization explicitly or change any registration code.

By comparing these approaches, it’s clear FastMCP’s design can significantly streamline development of MCP servers. It leverages **Scala 3 macros for compile-time meta-programming**, turning high-level annotated Scala code into the boilerplate Java code and JSON that the MCP protocol expects. This reduces errors, improves clarity, and accelerates development. Official MCP documentation emphasizes providing clear schemas and examples for the model ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=When%20implementing%20tools%3A)) – FastMCP would make it easier to follow those best practices (like ensuring all fields have descriptions, etc., which we could even enforce or default intelligently).

In conclusion, **FastMCP** would act as a thin but powerful layer on top of Anthropic’s MCP Java SDK, harnessing Scala’s strengths to create a more developer-friendly API. By allowing tools, prompts, and resources to be defined with simple annotations and leveraging compile-time code generation, it would remove much of the ceremony involved in extending AI assistants with new capabilities. This encourages experimentation and rapid development of rich context-providing servers, all while remaining fully compatible with the MCP standard and its ecosystem. The end result: developers can focus on *what* the AI can do (the logic of tools and the content of prompts) rather than the *how* (the plumbing of JSON schemas and registration), which aligns perfectly with the high-level spirit of MCP – integrating AI seamlessly with the world’s data and functions.

**Sources:**

- Anthropic, *Model Context Protocol* – Open standard for connecting AI with tools/data ([Introducing the Model Context Protocol \ Anthropic](https://www.anthropic.com/news/model-context-protocol#:~:text=Today%2C%20we%27re%20open,produce%20better%2C%20more%20relevant%20responses)) ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20MCP%20Server%20is%20a,of%20the%20protocol%2C%20responsible%20for))
- Anthropic, *MCP Java SDK Reference* – SDK features and usage (tools, resources, prompts) ([Overview - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-overview#:~:text=,Sampling%20support%20for%20AI%20model)) ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=The%20Tool%20specification%20includes%20a,a%20map%20of%20tool%20arguments))
- Anthropic, *MCP Concepts: Tools & Prompts* – Tool schema and prompt structure in MCP ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=Each%20tool%20is%20defined%20with,the%20following%20structure)) ([Prompts - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/prompts#:~:text=,Whether%20argument%20is%20required))
- Anthropic, *MCP Documentation* – Example of defining tools, resources, and prompts in Java SDK ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=%2F%2F%20Sync%20tool%20specification%20var,)) ([MCP Server - Model Context Protocol](https://modelcontextprotocol.io/sdk/java/mcp-server#:~:text=%2F%2F%20Sync%20prompt%20specification%20var,description%2C%20messages))
- GrizzlyPeak Software, *MCP Deep Dive* – Explanation of MCP tools, resources, prompts and their purpose ([Understanding the Model Context Protocol (MCP) and Building Your First Memory Server - Grizzly Peak Software](https://grizzlypeaksoftware.com/articles?id=4Tyr7iByM6tvJI1WzshwsC#:~:text=Each%20tool%20has%20a%20name%2C,the%20tools%20with%20appropriate%20arguments)) ([Understanding the Model Context Protocol (MCP) and Building Your First Memory Server - Grizzly Peak Software](https://grizzlypeaksoftware.com/articles?id=4Tyr7iByM6tvJI1WzshwsC#:~:text=1))
- Model Context Protocol Spec – Standardized endpoints (`tools/list`, `tools/call`, `prompts/get`, etc.) enable discovery and invocation flows ([Tools - Model Context Protocol](https://modelcontextprotocol.org/docs/concepts/tools#:~:text=,calculations%20to%20complex%20API%20interactions))