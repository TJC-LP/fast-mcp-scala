package com.tjclp.fastmcp
package examples

import zio.*

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.RegistrationMacro.*
import com.tjclp.fastmcp.server.*

/** Example server demonstrating the enhanced @Param annotation features.
  *
  * This example showcases:
  *   1. `examples` field - adds examples array to JSON schema (multiple values supported) 2.
  *      `required` field - override required status 3. `schema` field - custom JSON Schema override
  *
  * To run with MCP Inspector:
  * {{{
  * npx @modelcontextprotocol/inspector java -cp out/fast-mcp-scala/assembly.dest/out.jar \
  *   com.tjclp.fastmcp.examples.ParamFeaturesServer
  * }}}
  */
object ParamFeaturesServer extends ZIOAppDefault:

  /** Tool demonstrating the `examples` field.
    *
    * Check the JSON schema in the inspector - you should see an "examples" array with multiple
    * values for each parameter.
    */
  @Tool(
    name = Some("create_user"),
    description = Some("Create a new user account with example values")
  )
  def createUser(
      @Param(
        description = "Username for the account",
        examples = List("john_doe", "jane_smith")
      )
      username: String,
      @Param(
        description = "User's email address",
        examples = List("john@example.com", "jane@example.com")
      )
      email: String,
      @Param(
        description = "User's age in years",
        examples = List("25", "30", "42")
      )
      age: Int
  ): String =
    s"Created user '$username' with email '$email' (age: $age)"

  /** Tool demonstrating the `required` field.
    *
    * The `bio` parameter is optional (required=false) even though it doesn't have a default value.
    * This is useful when you want to make a parameter optional but handle the None case explicitly.
    */
  @Tool(
    name = Some("update_profile"),
    description = Some("Update user profile with optional fields")
  )
  def updateProfile(
      @Param(description = "Username to update", required = true)
      username: String,
      @Param(
        description = "New display name (optional)",
        required = false,
        examples = List("John Doe", "Jane Smith")
      )
      displayName: Option[String],
      @Param(
        description = "User biography (optional)",
        required = false,
        examples = List("Software developer from SF", "Product manager with 10 years experience")
      )
      bio: Option[String]
  ): String =
    val updates = List(
      displayName.map(d => s"display name set to '$d'"),
      bio.map(b => s"bio set to '$b'")
    ).flatten
    if updates.isEmpty then s"No updates for user '$username'"
    else s"Updated $username: ${updates.mkString(", ")}"

  /** Tool demonstrating the `schema` field with custom JSON Schema.
    *
    * The `priority` parameter has a custom schema that restricts it to specific enum values. This
    * is useful when you want more control over the schema than the default generation provides.
    */
  @Tool(
    name = Some("create_task"),
    description = Some("Create a task with custom status and priority enums")
  )
  def createTask(
      @Param(
        description = "Task name",
        examples = List("Fix bug #123", "Implement feature X", "Write documentation")
      )
      name: String,
      @Param(
        description = "Current status of the task",
        schema = Some(
          """{"type": "string", "enum": ["todo", "in_progress", "review", "done"], "description": "Current status"}"""
        )
      )
      status: String,
      @Param(
        description = "Task priority level",
        schema = Some(
          """{"type": "string", "enum": ["low", "medium", "high", "urgent"], "default": "medium"}"""
        ),
        required = false
      )
      priority: Option[String]
  ): String =
    val priorityStr = priority.getOrElse("medium")
    s"Created task '$name' with status '$status' and priority '$priorityStr'"

  /** Tool combining all @Param features.
    *
    * This demonstrates using examples, required, and schema together.
    */
  @Tool(
    name = Some("advanced_search"),
    description = Some("Advanced search demonstrating all @Param features")
  )
  def advancedSearch(
      @Param(
        description = "Search query",
        examples =
          List("scala functional programming", "machine learning tutorial", "rust concurrency"),
        required = true
      )
      query: String,
      @Param(
        description = "Maximum number of results",
        examples = List("10", "25", "50"),
        required = false
      )
      limit: Option[Int],
      @Param(
        description = "Sort order for results",
        schema = Some(
          """{"type": "string", "enum": ["relevance", "date", "popularity"], "default": "relevance"}"""
        ),
        required = false,
        examples = List("relevance", "date")
      )
      sortBy: Option[String],
      @Param(
        description = "Filter by category",
        schema = Some(
          """{"type": "array", "items": {"type": "string", "enum": ["tutorial", "documentation", "blog", "video"]}}"""
        ),
        required = false
      )
      categories: Option[List[String]]
  ): String =
    val limitStr = limit.map(l => s"limit=$l").getOrElse("no limit")
    val sortStr = sortBy.getOrElse("relevance")
    val catStr =
      categories.map(cats => s"categories=[${cats.mkString(", ")}]").getOrElse("all categories")
    s"Searching for '$query' with $limitStr, sorted by $sortStr, $catStr"

  override def run =
    for
      server <- ZIO.succeed(FastMcpServer("ParamFeaturesServer", "0.2.2"))
      _ <- ZIO.attempt(server.scanAnnotations[ParamFeaturesServer.type])
      _ <- server.runStdio()
    yield ()
