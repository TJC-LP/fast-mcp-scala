package com.tjclp.fastmcp
package macros

import com.tjclp.fastmcp.core.*
import com.tjclp.fastmcp.macros.MacpRegistrationMacro.scanAnnotations
import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.ResourceArgument
import org.scalatest.funsuite.AnyFunSuite
import zio.*

/** Integration test for Resource annotation and processor This tests the full workflow of resource
  * annotation processing
  */
class ResourceProcessorTest extends AnyFunSuite {
  // Create a server for testing
  val testServer = ResourceProcessorTest.server

  // Test that resource annotations are processed correctly
  test("should process resource annotations and register resources") {
    // First check that no resources are registered
    val initialResources = testServer.resourceManager.listDefinitions()
    assert(initialResources.isEmpty)

    // Process resource annotations
    testServer.scanAnnotations[ResourceProcessorTest.type]

    // Check resources were registered
    val resources = testServer.resourceManager.listDefinitions()
    assert(resources.size == 3)

    // Find static resource
    val staticResource = resources.find(_.uri == "static://greeting")
    assert(staticResource.isDefined)
    assert(staticResource.get.name.contains("Greeting"))
    assert(staticResource.get.isTemplate == false)
    assert(staticResource.get.mimeType.contains("text/plain"))

    // Find user template resource
    val userTemplate = resources.find(_.uri == "users://{userId}")
    assert(userTemplate.isDefined)
    assert(userTemplate.get.isTemplate)

    // Check that template has the correct arguments
    assert(userTemplate.get.arguments.isDefined)
    val userArguments = userTemplate.get.arguments.get
    assert(userArguments.length == 1)
    assert(userArguments.head.name == "userId")
    assert(userArguments.head.description.contains("The user ID"))

    // Find item template resource
    val itemTemplate = resources.find(_.uri == "items://{category}/{itemId}")
    assert(itemTemplate.isDefined)
    assert(itemTemplate.get.isTemplate)

    // Check item template has the correct arguments
    assert(itemTemplate.get.arguments.isDefined)
    val itemArguments = itemTemplate.get.arguments.get
    assert(itemArguments.length == 2)
    assert(itemArguments.exists(_.name == "category"))
    assert(itemArguments.exists(_.name == "itemId"))
  }

  // Test static resource access
  test("should correctly access static resources") {
    // Process annotations first to ensure resources are available
    testServer.scanAnnotations[ResourceProcessorTest.type]

    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          testServer.resourceManager.readResource("static://greeting", None)
        )
        .getOrThrowFiberFailure()
    }

    assert(result.isInstanceOf[String])
    assert(result.asInstanceOf[String] == "Hello from ResourceProcessorTest!")
  }

  // Test templated resource access with parameter
  test("should correctly access templated resources with parameters") {
    // Process annotations first to ensure resources are available
    testServer.scanAnnotations[ResourceProcessorTest.type]

    // Access user resource with userId parameter
    val userResult = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run {
          for {
            matchResult <- ZIO.fromOption(
              testServer.resourceManager.findMatchingTemplate("users://123")
            )
            (_, definition, handler, params) = matchResult
            result <- handler(Map("userId" -> "123"))
          } yield result
        }
        .getOrThrowFiberFailure()
    }

    // Check that we received the correct result
    val resultStr = userResult.toString
    assert(resultStr.contains("id -> 123"), "Result should contain user ID")
    assert(resultStr.contains("name -> User 123"), "Result should contain user name")

    // Access item resource with multiple parameters
    val itemResult = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run {
          for {
            matchResult <- ZIO.fromOption(
              testServer.resourceManager.findMatchingTemplate("items://electronics/phone-123")
            )
            (_, definition, handler, params) = matchResult
            result <- handler(Map("category" -> "electronics", "itemId" -> "phone-123"))
          } yield result
        }
        .getOrThrowFiberFailure()
    }

    assert(itemResult.isInstanceOf[String])
    assert(itemResult.asInstanceOf[String] == "Item phone-123 in category electronics")
  }
}

/** Companion object for ResourceProcessorTest containing the resources to be processed by
  * annotations
  */
object ResourceProcessorTest {
  // Create a test server for resource registration
  val server = new FastMcpServer("TestServer", "0.1.0")

  /** A static resource for testing
    */
  @Resource(
    uri = "static://greeting",
    name = Some("Greeting"),
    description = Some("A static greeting message"),
    mimeType = Some("text/plain")
  )
  def staticGreeting(): String = "Hello from ResourceProcessorTest!"

  /** A templated resource for testing with a single parameter
    */
  @Resource(
    uri = "users://{userId}",
    name = Some("UserInfo"),
    description = Some("Information about a user"),
    mimeType = Some("application/json")
  )
  def userResource(
      @ResourceParam("The user ID") userId: String
  ): Map[String, String] = {
    Map("id" -> userId, "name" -> s"User $userId", "role" -> "member")
  }

  /** A templated resource with multiple parameters
    */
  @Resource(
    uri = "items://{category}/{itemId}",
    mimeType = Some("text/plain")
  )
  def itemResource(
      @ResourceParam("The category of the item") category: String,
      @ResourceParam("The unique identifier for the item") itemId: String
  ): String = {
    s"Item $itemId in category $category"
  }
}
