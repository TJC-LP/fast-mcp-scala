package com.tjclp.fastmcp.core.skills

import java.nio.charset.StandardCharsets

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.skills.SkillTestFixtures.jdkSha256Hex

/** The portable SHA-256 against the NIST FIPS 180-4 example vectors and, on the JVM, against
  * `MessageDigest` over random inputs — two independent sources, so a transcription error in either
  * the digest or the tests cannot cancel out. Plus the strict UTF-8 validator's edge cases.
  */
class Sha256AndUtf8Test extends AnyFunSuite with Matchers:

  private def hex(s: String): String = Sha256.hex(Sha256.digest(s.getBytes(StandardCharsets.US_ASCII)))

  test("NIST FIPS 180-4 example vectors") {
    hex("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    hex("") shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq") shouldBe
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    hex("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu") shouldBe
      "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1"
    // One million 'a' (FIPS 180-4 / RFC 6234 test 3).
    Sha256.hex(Sha256.digest(Array.fill[Byte](1_000_000)('a'.toByte))) shouldBe
      "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
  }

  test("agrees with java.security.MessageDigest across padding boundaries and random inputs") {
    val rnd = new scala.util.Random(2640)
    val lengths = (0 to 130).toList ++ List(255, 256, 257, 511, 512, 513, 1000, 4095, 4096, 65536, 100_003)
    lengths.foreach { n =>
      val bytes = new Array[Byte](n)
      rnd.nextBytes(bytes)
      withClue(s"length $n")(Sha256.hex(Sha256.digest(bytes)) shouldBe jdkSha256Hex(bytes))
    }
  }

  test("formatted digests are sha256: + 64 lowercase hex, using the supplied hasher") {
    val d = Sha256.formatted("abc".getBytes(StandardCharsets.US_ASCII))
    d shouldBe "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    com.tjclp.fastmcp.core.wire.Skills.isValidDigest(d) shouldBe true
    com.tjclp.fastmcp.core.wire.Skills.isValidDigest(d.toUpperCase) shouldBe false
    com.tjclp.fastmcp.core.wire.Skills.isValidDigest("sha256:" + "0" * 63) shouldBe false
    com.tjclp.fastmcp.core.wire.Skills.isValidDigest("md5:" + "0" * 64) shouldBe false
    Sha256.formatted("abc".getBytes, _ => Array.fill[Byte](32)(0)) shouldBe "sha256:" + "0" * 64
  }

  test("strict UTF-8 accepts well-formed multibyte text, CRLF and NUL") {
    val ok = "Grüße 名前 🎉\r\n\u0000tab\t".getBytes(StandardCharsets.UTF_8)
    Utf8.decodeStrict(ok).map(_.getBytes(StandardCharsets.UTF_8).toList) shouldBe Right(ok.toList)
    Utf8.isValid(Array.empty[Byte]) shouldBe true
    Utf8.isValid(Array[Byte](0xf4.toByte, 0x8f.toByte, 0xbf.toByte, 0xbf.toByte)) shouldBe true // U+10FFFF
  }

  test("strict UTF-8 rejects overlongs, surrogates, truncation, stray continuations and > U+10FFFF") {
    val bad: List[(String, Array[Byte])] = List(
      "overlong 2-byte" -> Array(0xc0, 0x80),
      "overlong 3-byte" -> Array(0xe0, 0x80, 0x80),
      "overlong 4-byte" -> Array(0xf0, 0x80, 0x80, 0x80),
      "surrogate" -> Array(0xed, 0xa0, 0x80),
      "truncated" -> Array(0xe2, 0x82),
      "stray continuation" -> Array(0x80),
      "above U+10FFFF" -> Array(0xf4, 0x90, 0x80, 0x80),
      "0xf5 lead" -> Array(0xf5, 0x80, 0x80, 0x80),
      "0xff" -> Array(0xff)
    ).map { case (n, ints) => n -> ints.map(_.toByte) }
    bad.foreach { case (name, bytes) => withClue(name)(Utf8.decodeStrict(bytes).isLeft shouldBe true) }
    Utf8.decodeStrict("ok".getBytes ++ Array(0xc0.toByte, 0x80.toByte)) shouldBe Left(2)
  }
