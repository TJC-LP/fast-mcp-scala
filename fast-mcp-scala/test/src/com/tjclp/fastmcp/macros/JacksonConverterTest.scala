package com.tjclp.fastmcp.macros

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

object JacksonConverterTestFixtures:
  enum RenderMode:
    case plain, bold

  case class Address(city: String, postalCode: Option[String] = None)
  case class UserProfile(
      name: String,
      age: Int = 18,
      address: Address,
      aliases: List[String] = Nil
  )

class JacksonConverterTest extends AnyFunSuite with Matchers:

  import JacksonConverterTestFixtures.*

  private val context = JacksonConversionContext.default

  test("default product converter handles nested case classes and defaults") {
    val raw = Map(
      "name" -> "Alice",
      "address" -> Map("city" -> "New York")
    )

    val profile = summon[JacksonConverter[UserProfile]].convert("profile", raw, context)

    profile shouldBe UserProfile(
      name = "Alice",
      age = 18,
      address = Address("New York", None),
      aliases = Nil
    )
  }

  test("default fallback converter handles Scala 3 enum values") {
    val mode = summon[JacksonConverter[RenderMode]].convert("mode", "bold", context)
    mode shouldBe RenderMode.bold
  }
