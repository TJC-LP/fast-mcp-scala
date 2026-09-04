package com.tjclp.fastmcp
package server.transport

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Source-scan guard: every `MessageLoop.parseFrame(` call site outside `MessageLoop.scala` itself
  * must pass `router.limits`, otherwise a transport silently falls back to the default limits and
  * ignores the operator's `McpServerSettings.limits`. Protects the cross-arc one-liners across the
  * transport merges (TJC-2294).
  */
class LimitsWiringTest extends AnyFunSuite with Matchers:

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p =>
        Files.exists(p.resolve("build.mill")) && Files.isDirectory(p.resolve("fast-mcp-scala"))
      )
      .getOrElse(fail("could not locate the repository root (build.mill) from user.dir"))

  test("every parseFrame call site outside MessageLoop passes router.limits") {
    val root = repoRoot.resolve("fast-mcp-scala")
    val platforms = List("jvm", "js", "shared", "native")
    val offenders = platforms.flatMap { platform =>
      val src = root.resolve(platform).resolve("src")
      if !Files.isDirectory(src) then Nil
      else
        val files = Files.walk(src).iterator().asScala.filter(_.toString.endsWith(".scala")).toList
        files.flatMap { file =>
          if file.getFileName.toString == "MessageLoop.scala" then Nil
          else
            Files
              .readAllLines(file)
              .asScala
              .zipWithIndex
              .collect {
                case (line, idx)
                    if line.contains("parseFrame(") && !line.contains("router.limits") =>
                  s"$file:${idx + 1}: $line"
              }
        }
    }
    withClue(offenders.mkString("\n")) {
      offenders shouldBe empty
    }
  }

  test("the transports actually reference parseFrame (the guard is not vacuous)") {
    val root = repoRoot.resolve("fast-mcp-scala")
    val hits = List("jvm", "js").flatMap { platform =>
      Files
        .walk(root.resolve(platform).resolve("src"))
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".scala"))
        .count(f => Files.readString(f).contains("parseFrame(")) :: Nil
    }
    hits.sum should be >= 2
  }
