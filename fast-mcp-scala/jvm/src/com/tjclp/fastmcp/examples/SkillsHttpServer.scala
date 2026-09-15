package com.tjclp.fastmcp
package examples

import com.tjclp.fastmcp.*

/** The [[SkillsServer]] catalog over streamable HTTP (JVM). Same skills, same tool, same dynamic
  * provider — this is the fixture `docs/skills-conformance.md` runs the upstream
  * `sep-2640-skills-*` conformance scenarios against:
  *
  * {{{
  *   ./mill fast-mcp-scala.jvm.runMain com.tjclp.fastmcp.examples.SkillsHttpServer
  *   # then, from a checkout of modelcontextprotocol/conformance:
  *   npm start -- server --scenario 'sep-2640-skills-*' --url http://127.0.0.1:8091/mcp
  * }}}
  *
  * Binds `127.0.0.1:8091` (override with `FAST_MCP_SKILLS_PORT`).
  */
object SkillsHttpServer extends McpServerApp[Http, SkillsHttpServer.type]:

  override def name: String = SkillsServer.name

  override def settings: McpServerSettings =
    McpServerSettings(
      host = "127.0.0.1",
      port = sys.env.get("FAST_MCP_SKILLS_PORT").flatMap(_.toIntOption).getOrElse(8091),
      allowedHosts = Some(Set("127.0.0.1", "localhost", "[::1]"))
    )

  @Tool(name = Some("add"), description = Some("Add two numbers"))
  def add(@Param("First number") a: Int, @Param("Second number") b: Int): Int = a + b

  override def skills: List[McpSkill] = SkillsServer.skills

  override def skillProviders: List[SkillProvider[Any]] = SkillsServer.skillProviders
