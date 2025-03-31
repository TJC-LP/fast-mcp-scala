ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.6.4" // Using Scala 3

val zioVersion = "2.1.16" // Specify ZIO version

lazy val root = (project in file("."))
  .settings(
    name := "fast-mcp-scala",
    // Enable Scala 3 macros
    scalacOptions ++= Seq("-Xcheck-macros"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-json" % "0.7.39",
      "io.modelcontextprotocol.sdk" % "mcp" % "0.8.1"
    ),
    // Set the main class for 'sbt run'
    Compile / run / mainClass := Some("fastmcp.FastMCPMain")
  )