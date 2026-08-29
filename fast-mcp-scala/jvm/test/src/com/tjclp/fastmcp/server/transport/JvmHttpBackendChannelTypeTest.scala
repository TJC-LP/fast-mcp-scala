package com.tjclp.fastmcp
package server.transport

import org.scalatest.funsuite.AnyFunSuite
import zio.http.netty.ChannelType

/** Truth table for [[JvmHttpBackend.channelTypeFor]]: AUTO on a plain JVM (zio-http's default,
  * epoll/kqueue when available), NIO inside a GraalVM native image (AUTO's runtime transport
  * probing is what closed-world analysis can't tolerate), `-Dfastmcp.http.channelType` overriding
  * both.
  */
class JvmHttpBackendChannelTypeTest extends AnyFunSuite:

  test("plain JVM with no override keeps zio-http's AUTO default") {
    assert(JvmHttpBackend.channelTypeFor(None, inNativeImage = false) == ChannelType.AUTO)
  }

  test("native image with no override pins NIO") {
    assert(JvmHttpBackend.channelTypeFor(None, inNativeImage = true) == ChannelType.NIO)
  }

  test("explicit override wins in both environments") {
    for inNative <- List(false, true) do
      assert(JvmHttpBackend.channelTypeFor(Some("nio"), inNative) == ChannelType.NIO)
      assert(JvmHttpBackend.channelTypeFor(Some("epoll"), inNative) == ChannelType.EPOLL)
      assert(JvmHttpBackend.channelTypeFor(Some("kqueue"), inNative) == ChannelType.KQUEUE)
      assert(JvmHttpBackend.channelTypeFor(Some("auto"), inNative) == ChannelType.AUTO)
  }

  test("override matching is trimmed and case-insensitive") {
    assert(JvmHttpBackend.channelTypeFor(Some(" NIO "), inNativeImage = false) == ChannelType.NIO)
    assert(JvmHttpBackend.channelTypeFor(Some("Epoll"), inNativeImage = true) == ChannelType.EPOLL)
  }

  test("unrecognized override falls back to the environment default") {
    assert(JvmHttpBackend.channelTypeFor(Some("io_uring"), inNativeImage = false) == ChannelType.AUTO)
    assert(JvmHttpBackend.channelTypeFor(Some("io_uring"), inNativeImage = true) == ChannelType.NIO)
    assert(JvmHttpBackend.channelTypeFor(Some(""), inNativeImage = true) == ChannelType.NIO)
  }
