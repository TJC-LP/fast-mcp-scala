package com.tjclp.fastmcp
package examples

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import zio.*

import com.tjclp.fastmcp.server.*
import com.tjclp.fastmcp.server.manager.*
import com.tjclp.fastmcp.server.transport.JvmTransportBackend.given

/** Resource-template registration and parameter extraction through the native managers. */
class ResourceTemplateTest extends AnyFunSuite with Matchers:

  private def runUnsafe[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure())

  test("Resource templates are registered correctly") {
    val server = McpServer("TestServer")

    runUnsafe(
      server.resourceTemplate(
        uriPattern = "users://{userId}",
        handler = (params: Map[String, String]) => ZIO.succeed(s"User: ${params("userId")}"),
        name = Some("GetUser"),
        description = Some("Get user by ID"),
        arguments = Some(List(ResourceArgument("userId", Some("User ID"), true)))
      )
    )

    val templates = server.resourceManager.listTemplateResources()
    val resources = server.resourceManager.listStaticResources()

    templates.size shouldBe 1
    templates.head.uri shouldBe "users://{userId}"
    templates.head.name shouldBe Some("GetUser")
    resources.size shouldBe 0 // templates should not appear in the static resources list
  }

  test("Resource templates handle parameters correctly") {
    val server = McpServer("TestServer")

    runUnsafe(
      server.resourceTemplate(
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
    )

    val result = runUnsafe(server.resourceManager.readResource("repos://github/fastmcp/issues/123", None))
    result shouldBe "Issue 123 in github/fastmcp"
  }
