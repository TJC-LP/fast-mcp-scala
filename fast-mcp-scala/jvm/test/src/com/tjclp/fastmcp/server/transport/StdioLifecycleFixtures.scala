package com.tjclp.fastmcp
package server.transport

import zio.*

import com.tjclp.fastmcp.{*, given}

/** Subprocess fixture for [[StdioProcessLifecycleTest]]: a real stdio server, because what is under
  * test — exit on a signal while stdin is open, replies written before the process ends — only
  * exists in a process of its own.
  */
object LifecycleStdioApp extends McpServerApp[Stdio, LifecycleStdioApp.type]:

  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(@Param("First operand") a: Int, @Param("Second operand") b: Int): Int = a + b

  /** Still running when stdin ends in the burst test: its reply is owed all the same. */
  @Tool(name = Some("slow"), description = Some("Answers after a second and a half"))
  def slow(): ZIO[Any, Nothing, String] = ZIO.sleep(1500.millis).as("slow done")

  /** Says on stderr that it runs, then never answers. */
  @Tool(name = Some("hang"), description = Some("Never answers"))
  def hang(): ZIO[Any, Nothing, String] = ZIO.logInfo("hang started") *> ZIO.never
