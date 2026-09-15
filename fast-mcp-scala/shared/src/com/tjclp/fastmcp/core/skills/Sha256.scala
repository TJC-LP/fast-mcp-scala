package com.tjclp.fastmcp.core.skills

import com.tjclp.fastmcp.core.wire.Skills

/** Portable SHA-256 (FIPS PUB 180-4 §6.2) in plain Scala — the digest the Skills extension puts in
  * every `SkillResource`.
  *
  * Why a hand-written primitive exists in a library that otherwise avoids them: Scala.js and Scala
  * Native ship no `java.security.MessageDigest`, and the only cross-published alternatives are
  * either unmaintained or heavier than the 80 lines below. The JVM backend overrides
  * `TransportBackend.sha256` with `MessageDigest`, so on the JVM this implementation is exercised
  * only by the verifier default and by the tests that pin it against `MessageDigest` and the NIST
  * FIPS 180-4 example vectors (`Sha256VectorsTest` on every platform).
  *
  * Straight transcription of the standard: message padding to 512-bit blocks with the 64-bit
  * big-endian length, 64 rounds over the eight working variables with the published round constants
  * (first 32 bits of the fractional parts of the cube roots of the first 64 primes). No table is
  * derived at runtime, so a transcription error is caught by the vectors, not hidden by a
  * self-consistent generator.
  */
object Sha256:

  private val K: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  )

  private val H0: Array[Int] = Array(
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  )

  /** The 32-byte SHA-256 digest of `input`. */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  def digest(input: Array[Byte]): Array[Byte] =
    val bitLength = input.length.toLong * 8L
    // Padding: 0x80, zeros to 56 mod 64, then the 64-bit big-endian bit length.
    val padded = new Array[Byte](((input.length + 8) / 64 + 1) * 64)
    java.lang.System.arraycopy(input, 0, padded, 0, input.length)
    padded(input.length) = 0x80.toByte
    var i = 0
    while i < 8 do
      padded(padded.length - 1 - i) = ((bitLength >>> (8 * i)) & 0xff).toByte
      i += 1

    val h = H0.clone()
    val w = new Array[Int](64)
    var block = 0
    while block < padded.length do
      var t = 0
      while t < 16 do
        val o = block + t * 4
        w(t) = ((padded(o) & 0xff) << 24) | ((padded(o + 1) & 0xff) << 16) |
          ((padded(o + 2) & 0xff) << 8) | (padded(o + 3) & 0xff)
        t += 1
      while t < 64 do
        val s0 =
          Integer.rotateRight(w(t - 15), 7) ^ Integer.rotateRight(w(t - 15), 18) ^ (w(t - 15) >>> 3)
        val s1 =
          Integer.rotateRight(w(t - 2), 17) ^ Integer.rotateRight(w(t - 2), 19) ^ (w(t - 2) >>> 10)
        w(t) = w(t - 16) + s0 + w(t - 7) + s1
        t += 1

      var a = h(0); var b = h(1); var c = h(2); var d = h(3)
      var e = h(4); var f = h(5); var g = h(6); var hh = h(7)
      t = 0
      while t < 64 do
        val bigS1 =
          Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25)
        val ch = (e & f) ^ (~e & g)
        val temp1 = hh + bigS1 + ch + K(t) + w(t)
        val bigS0 =
          Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22)
        val maj = (a & b) ^ (a & c) ^ (b & c)
        val temp2 = bigS0 + maj
        hh = g; g = f; f = e; e = d + temp1
        d = c; c = b; b = a; a = temp1 + temp2
        t += 1
      h(0) += a; h(1) += b; h(2) += c; h(3) += d
      h(4) += e; h(5) += f; h(6) += g; h(7) += hh
      block += 64

    val out = new Array[Byte](32)
    i = 0
    while i < 8 do
      out(i * 4) = (h(i) >>> 24).toByte
      out(i * 4 + 1) = (h(i) >>> 16).toByte
      out(i * 4 + 2) = (h(i) >>> 8).toByte
      out(i * 4 + 3) = h(i).toByte
      i += 1
    out

  /** Lowercase hex rendering of a digest. */
  def hex(bytes: Array[Byte]): String =
    val sb = new StringBuilder(bytes.length * 2)
    bytes.foreach { b =>
      sb.append(Character.forDigit((b >> 4) & 0xf, 16))
      sb.append(Character.forDigit(b & 0xf, 16))
    }
    sb.result()

  /** `sha256:<64 lowercase hex>` of `input`, using `hasher` (the portable digest by default; the
    * server supplies the platform's `TransportBackend.sha256`).
    */
  def formatted(input: Array[Byte], hasher: Array[Byte] => Array[Byte] = digest): String =
    Skills.DigestPrefix + hex(hasher(input))
