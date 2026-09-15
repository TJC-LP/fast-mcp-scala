package com.tjclp.fastmcp.core.skills

import java.nio.charset.StandardCharsets

/** Strict UTF-8 validation for skill text (`SKILL.md` and text supporting files). The platform
  * decoders replace malformed input silently by default; a skill whose bytes cannot round-trip
  * through `text` on the wire would then be served as different bytes than its digest describes, so
  * decoding here fails closed on:
  *
  *   - truncated or stray continuation bytes,
  *   - overlong encodings (`C0 80`, `E0 80 80`, `F0 80 80 80`),
  *   - encoded UTF-16 surrogates (`ED A0 80` .. `ED BF BF`),
  *   - code points above U+10FFFF (`F4 90 ...`, `F5`..`FF` lead bytes).
  *
  * NUL bytes are valid UTF-8 and are accepted (they are legitimate in a text file's content).
  */
object Utf8:

  /** `Right(text)` when `bytes` are well-formed UTF-8; `Left(offset)` of the first malformed byte
    * otherwise.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.Return"))
  def decodeStrict(bytes: Array[Byte]): Either[Int, String] =
    val n = bytes.length
    var i = 0
    while i < n do
      val b0 = bytes(i) & 0xff
      if b0 < 0x80 then i += 1
      else if b0 < 0xc2 then return Left(i) // continuation byte or overlong 2-byte lead
      else if b0 < 0xe0 then
        if i + 1 >= n || !isCont(bytes(i + 1)) then return Left(i)
        i += 2
      else if b0 < 0xf0 then
        if i + 2 >= n then return Left(i)
        val b1 = bytes(i + 1) & 0xff
        if !isCont(bytes(i + 1)) || !isCont(bytes(i + 2)) then return Left(i)
        if b0 == 0xe0 && b1 < 0xa0 then return Left(i) // overlong
        if b0 == 0xed && b1 >= 0xa0 then return Left(i) // surrogate
        i += 3
      else if b0 < 0xf5 then
        if i + 3 >= n then return Left(i)
        val b1 = bytes(i + 1) & 0xff
        if !isCont(bytes(i + 1)) || !isCont(bytes(i + 2)) || !isCont(bytes(i + 3)) then
          return Left(i)
        if b0 == 0xf0 && b1 < 0x90 then return Left(i) // overlong
        if b0 == 0xf4 && b1 >= 0x90 then return Left(i) // > U+10FFFF
        i += 4
      else return Left(i)
    Right(new String(bytes, StandardCharsets.UTF_8))

  private def isCont(b: Byte): Boolean = (b & 0xc0) == 0x80

  def isValid(bytes: Array[Byte]): Boolean = decodeStrict(bytes).isRight

  def encode(text: String): Array[Byte] = text.getBytes(StandardCharsets.UTF_8)
