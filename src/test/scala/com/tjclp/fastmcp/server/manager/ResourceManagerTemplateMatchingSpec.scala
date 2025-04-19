package com.tjclp.fastmcp.server.manager

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

/** Tests for ResourceTemplatePattern matching and ResourceManager resolution behavior.
  */
class ResourceManagerTemplateMatchingSpec extends AnyFlatSpec with Matchers {

  "ResourceTemplatePattern" should "match single placeholder and extract params" in {
    val pattern = ResourceTemplatePattern("users://{id}/profile")
    val m = pattern.matches("users://123/profile")
    m shouldBe defined
    val params = pattern.extractParams("users://123/profile", m.get)
    params shouldBe Map("id" -> "123")
  }

  it should "match multiple placeholders and extract params" in {
    val pattern = ResourceTemplatePattern("items://{cat}/{itemId}")
    val m = pattern.matches("items://books/xyz-987")
    m shouldBe defined
    val params = pattern.extractParams("items://books/xyz-987", m.get)
    params shouldBe Map("cat" -> "books", "itemId" -> "xyz-987")
  }

  it should "not match URIs not fitting pattern" in {
    val pattern = ResourceTemplatePattern("users://{id}/profile")
    pattern.matches("users://123/profile/extra") shouldBe None
  }

  "ResourceManager.readResource" should "prefer static resources over templates" in {
    val rm = new ResourceManager
    // Register static
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(
          rm.addResource(
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
          rm.addResourceTemplate(
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

  "getResourceDefinition / getResourceHandler" should "return Some for existing static resource" in {
    val rm = new ResourceManager
    val staticUri = "foo://static"
    val defn = ResourceDefinition(staticUri, Some("n"), Some("d"))

    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(rm.addResource(staticUri, () => ZIO.succeed("ok"), defn))
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

    // Non‑existent URI returns None
    rm.getResourceDefinition("missing://x") shouldBe None
    rm.getResourceHandler("missing://x") shouldBe None
  }
}
