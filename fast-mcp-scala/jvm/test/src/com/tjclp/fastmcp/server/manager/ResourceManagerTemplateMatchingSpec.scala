package com.tjclp.fastmcp.server.manager

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

/** Tests for ResourceManager template matching and resolution behavior.
  */
class ResourceManagerTemplateMatchingSpec extends AnyFlatSpec with Matchers {

  "extractTemplateParams" should "match single placeholder and extract params" in {
    val rm = new ResourceManager
    val params = rm.extractTemplateParams("users://{id}/profile", "users://123/profile")
    params shouldBe Some(Map("id" -> "123"))
  }

  it should "match multiple placeholders and extract params" in {
    val rm = new ResourceManager
    val params = rm.extractTemplateParams("items://{cat}/{itemId}", "items://books/xyz-987")
    params shouldBe Some(Map("cat" -> "books", "itemId" -> "xyz-987"))
  }

  it should "support placeholder names beyond \\w" in {
    val rm = new ResourceManager
    val params =
      rm.extractTemplateParams("repos://{owner-name}/{repo_name}", "repos://tjc-lp/fast_mcp")
    params shouldBe Some(Map("owner-name" -> "tjc-lp", "repo_name" -> "fast_mcp"))
  }

  it should "not match URIs not fitting pattern" in {
    val rm = new ResourceManager
    val params = rm.extractTemplateParams("users://{id}/profile", "users://123/profile/extra")
    params shouldBe None
  }

  "ResourceManager.readResource" should "prefer static resources over templates" in {
    val rm = new ResourceManager
    // Register static
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(
          rm.addStaticResource(
            "test://static",
            () => ZIO.succeed("static-content"),
            ResourceDefinition("test://static", None, None)
          )
        )
        .getOrThrowFiberFailure()
    }
    // Register template
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(
          rm.addTemplateResource(
            "test://{foo}",
            params => ZIO.succeed(s"template-${params("foo")}"),
            ResourceDefinition(
              "test://{foo}",
              None,
              None,
              mimeType = None,
              isTemplate = true,
              arguments = Some(List(ResourceArgument("foo", None, required = true)))
            )
          )
        )
        .getOrThrowFiberFailure()
    }
    // Static should win
    val res1 = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(rm.readResource("test://static", None)).getOrThrowFiberFailure()
    }
    res1 shouldBe "static-content"
    // Template fallback
    val res2 = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(rm.readResource("test://abc", None)).getOrThrowFiberFailure()
    }
    res2 shouldBe "template-abc"
  }

  it should "fail when no resource is found" in {
    val rm = new ResourceManager
    val ex = intercept[Throwable] {
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(rm.readResource("nope://x", None)).getOrThrowFiberFailure()
      }
    }
    ex.getMessage should include("not found")
  }

  it should "fail fast when template placeholders are missing matching arguments" in {
    val rm = new ResourceManager

    val ex = intercept[Throwable] {
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe
          .run(
            rm.addTemplateResource(
              "users://{user-id}",
              _ => ZIO.succeed("nope"),
              ResourceDefinition(
                "users://{user-id}",
                None,
                None,
                isTemplate = true,
                arguments = Some(List(ResourceArgument("userId", None, required = true)))
              )
            )
          )
          .getOrThrowFiberFailure()
      }
    }

    ex.getMessage should include("Failed to register resource template")
    rm.listTemplateDefinitions() shouldBe Nil
  }

  "getResourceDefinition / getResourceHandler" should "return Some for existing static resource" in {
    val rm = new ResourceManager
    val staticUri = "foo://static"
    val defn = ResourceDefinition(staticUri, Some("n"), Some("d"))

    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(rm.addStaticResource(staticUri, () => ZIO.succeed("ok"), defn))
        .getOrThrowFiberFailure()
    }

    rm.getResourceDefinition(staticUri) shouldBe Some(
      defn.copy(isTemplate = false, arguments = None)
    )
    val handlerOpt = rm.getResourceHandler(staticUri)
    handlerOpt shouldBe defined
    val result = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(handlerOpt.get()).getOrThrowFiberFailure()
    }
    result shouldBe "ok"

    // Non-existent URI returns None
    rm.getResourceDefinition("missing://x") shouldBe None
    rm.getResourceHandler("missing://x") shouldBe None
  }
}
