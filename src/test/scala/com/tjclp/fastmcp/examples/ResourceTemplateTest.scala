package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.*
import zio.*
import zio.test.*
import zio.test.Assertion.*

import scala.jdk.CollectionConverters.*

object ResourceTemplateTest extends ZIOSpecDefault:

  def spec = suite("ResourceTemplateTest")(
    test("Resource templates are registered correctly") {
      for {
        server <- ZIO.succeed(FastMcpServer("TestServer"))

        // Register a simple template
        _ <- server.resourceTemplate(
          uriPattern = "users://{userId}",
          handler = (params: Map[String, String]) => ZIO.succeed(s"User: ${params("userId")}"),
          name = Some("GetUser"),
          description = Some("Get user by ID"),
          arguments = Some(List(ResourceArgument("userId", Some("User ID"), true)))
        )

        // List templates
        templatesResult <- server.listResourceTemplates()
        templates = templatesResult.resourceTemplates().asScala.toList

        // List all resources
        resourcesResult <- server.listResources()
        resources = resourcesResult.resources().asScala.toList

      } yield {
        assert(templates.size)(equalTo(1)) &&
        assert(templates.head.uriTemplate())(equalTo("users://{userId}")) &&
        assert(templates.head.name())(equalTo("GetUser")) &&
        assert(resources.size)(equalTo(0)) // Templates should not appear in resources list
      }
    },
    test("Resource templates handle parameters correctly") {
      for {
        server <- ZIO.succeed(FastMcpServer("TestServer"))

        // Register a multi-param template
        _ <- server.resourceTemplate(
          uriPattern = "repos://{owner}/{repo}/issues/{id}",
          handler = (params: Map[String, String]) =>
            ZIO.succeed(s"Issue ${params("id")} in ${params("owner")}/${params("repo")}"),
          name = Some("GetIssue"),
          arguments = Some(
            List(
              ResourceArgument("owner", Some("Repository owner"), true),
              ResourceArgument("repo", Some("Repository name"), true),
              ResourceArgument("id", Some("Issue ID"), true)
            )
          )
        )

        // Test the handler
        result <- server.resourceManager.readResource("repos://github/fastmcp/issues/123", None)

      } yield assert(result)(equalTo("Issue 123 in github/fastmcp"))
    }
  )
