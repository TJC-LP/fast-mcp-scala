package fastmcp.compat

import java.io.File
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import com.typesafe.tools.mima.core.{IncompatibleSignatureProblem, Problem}
import com.typesafe.tools.mima.lib.MiMaLib

/** The binary compatibility check behind `./mill fast-mcp-scala.mimaCheck`.
  *
  * Arguments: `<exclusions file> <classpath file> <current jar> <version>=<released jar>...`
  *
  * For each released jar, MiMa lists what would stop a program compiled against it from linking
  * against the current jar, and what it will stop checking (a public member that became
  * package-private: `MethodNoLongerCheckedProblem`). A finding is accepted only when the exclusions
  * file names it, with a reason. The run fails (exit 1) on a finding that is not listed and on a
  * listed one that matches nothing (it is stale: remove it). Exit 2 is a usage error or a malformed
  * exclusions file, a line without a reason included.
  *
  * Exclusions file: blank lines and lines starting with `#` are ignored. Every other line is
  *
  * {{{
  * <ProblemType> <member>  # <reason>
  * }}}
  *
  * with the two names exactly as the report prints them.
  *
  * `IncompatibleSignatureProblem` is printed but never counted, which is also sbt-mima's default.
  * It means the erased JVM signature is the same and only the generic `Signature` attribute
  * differs, so linking is unaffected. scalac also writes that attribute differently in an
  * incremental build than in a clean one (seen on the `export`ed givens of `ExportsJvm`), so
  * counting it would make the result depend on what was last recompiled.
  */
object Mima:

  final case class Exclusion(problemType: String, member: String, reason: String, line: Int):

    def matches(problem: Problem): Boolean =
      problemType == typeOf(problem) && member == memberOf(problem)

  private def typeOf(problem: Problem): String = problem.getClass.getSimpleName
  private def memberOf(problem: Problem): String = problem.matchName.getOrElse("")

  def main(args: Array[String]): Unit =
    args.toList match
      case exclusionsFile :: classpathFile :: currentJar :: baselines if baselines.nonEmpty =>
        sys.exit(run(Path.of(exclusionsFile), Path.of(classpathFile), currentJar, baselines))
      case _ =>
        System.err.println(
          "usage: Mima <exclusions file> <classpath file> <current jar> <version>=<released jar>..."
        )
        sys.exit(2)

  private def run(
      exclusionsFile: Path,
      classpathFile: Path,
      currentJar: String,
      baselines: List[String]
  ): Int =
    parseExclusions(exclusionsFile) match
      case Left(errors) =>
        errors.foreach(e => System.err.println(s"$exclusionsFile: $e"))
        2
      case Right(exclusions) =>
        val classpath =
          Files.readAllLines(classpathFile).asScala.toList.filter(_.nonEmpty).map(new File(_))
        val reports = baselines.map { baseline =>
          val (version, jar) = baseline.splitAt(baseline.indexOf('='))
          val (signatureOnly, problems) = new MiMaLib(classpath)
            .collectProblems(new File(jar.drop(1)), new File(currentJar), Nil)
            .partition(_.isInstanceOf[IncompatibleSignatureProblem])
          signatureOnly.foreach { p =>
            println(s"MiMa: not counted against $version (generic signature only): ${memberOf(p)}")
          }
          version -> problems.sortBy(p => (memberOf(p), typeOf(p), p.description("current")))
        }
        val used = for
          (_, problems) <- reports
          problem <- problems
          exclusion <- exclusions.find(_.matches(problem))
        yield exclusion
        val stale = exclusions.filterNot(used.contains)

        val open = reports.map { (version, problems) =>
          val (excluded, remaining) = problems.partition(p => exclusions.exists(_.matches(p)))
          println(
            s"MiMa: current jar against $version: ${problems.size} finding(s), " +
              s"${excluded.size} accepted, ${remaining.size} open"
          )
          excluded.foreach { p =>
            val reason = exclusions.find(_.matches(p)).fold("")(_.reason)
            println(s"  accepted  ${typeOf(p)} ${memberOf(p)}  # $reason")
          }
          remaining.foreach { p =>
            println(s"  OPEN      ${typeOf(p)} ${memberOf(p)}")
            println(s"            ${p.description("the current sources")}")
          }
          remaining.size
        }.sum

        stale.foreach { e =>
          println(
            s"$exclusionsFile:${e.line}: stale exclusion, it matches no finding: " +
              s"${e.problemType} ${e.member}"
          )
        }
        if open > 0 then
          println(
            "Keep the old signature (see \"Binary compatibility\" in CONTRIBUTING.md), or add a " +
              s"line with a reason to $exclusionsFile."
          )
        if open > 0 || stale.nonEmpty then 1 else 0

  private def parseExclusions(file: Path): Either[List[String], List[Exclusion]] =
    val parsed = Files.readAllLines(file).asScala.toList.zipWithIndex.flatMap { (raw, index) =>
      val line = raw.trim
      if line.isEmpty || line.startsWith("#") then None
      else
        val (entry, comment) = line.splitAt(line.indexOf('#') match
          case -1 => line.length
          case at => at
        )
        val reason = comment.dropWhile(_ == '#').trim
        Some(entry.trim.split("\\s+").toList match
          case problemType :: member :: Nil if reason.nonEmpty =>
            Right(Exclusion(problemType, member, reason, index + 1))
          case _ :: _ :: Nil =>
            Left(s"line ${index + 1}: the exclusion has no reason (write it after a #)")
          case _ =>
            Left(s"line ${index + 1}: expected `<ProblemType> <member>  # <reason>`")
        )
    }
    val errors = parsed.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors) else Right(parsed.collect { case Right(e) => e })
