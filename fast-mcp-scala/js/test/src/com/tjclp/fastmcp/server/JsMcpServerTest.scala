package com.tjclp.fastmcp
package server

import scala.scalajs.js

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.tjclp.fastmcp.core.*

class JsMcpServerTest extends AnyFlatSpec with Matchers:

  private val taskAwareTool = ToolDefinition(
    name = "task-aware",
    description = Some("Supports task execution"),
    taskSupport = Some(TaskSupport.Optional)
  )

  "toolDefinitionToJs" should "omit execution metadata when tasks are disabled" in {
    val raw =
      JsMcpServer.toolDefinitionToJs(taskAwareTool, includeTaskSupport = false)
        .asInstanceOf[js.Dynamic]

    js.isUndefined(raw.selectDynamic("execution")) shouldBe true
  }

  it should "include execution metadata when tasks are enabled" in {
    val raw =
      JsMcpServer.toolDefinitionToJs(taskAwareTool, includeTaskSupport = true)
        .asInstanceOf[js.Dynamic]
    val execution = raw.selectDynamic("execution").asInstanceOf[js.Dynamic]

    execution.selectDynamic("taskSupport").asInstanceOf[String] shouldBe "optional"
  }
