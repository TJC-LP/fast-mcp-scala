package com.tjclp.fastmcp.core

/** FNV-1a 64-bit over UTF-8 bytes — pure Long arithmetic, identical on JVM and Scala.js. Used for
  * content-derived identifiers (MRTR input keys, test-fixture request-state signing); not a
  * cryptographic hash.
  */
private[fastmcp] object Fnv1a:

  def hex64(s: String): String =
    val h = s
      .getBytes(java.nio.charset.StandardCharsets.UTF_8)
      .foldLeft(0xcbf29ce484222325L)((acc, b) => (acc ^ (b & 0xffL)) * 0x100000001b3L)
    java.lang.Long.toHexString(h)
