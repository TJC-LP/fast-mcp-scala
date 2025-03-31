ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.6.4" // Using Scala 3

val zioVersion = "2.1.16" // Specify ZIO version
val zioSchemaVersion = "1.6.6" // ZIO Schema version

lazy val root = (project in file("."))
  .settings(
    name := "fast-mcp-scala",
    // Enable Scala 3 macros
    scalacOptions ++= Seq("-Xcheck-macros"),
    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-json" % "0.7.39",
      
      // ZIO Schema for schema generation
      "dev.zio" %% "zio-schema" % zioSchemaVersion,
      "dev.zio" %% "zio-schema-json" % zioSchemaVersion,
      "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,

      // Tapir
      "com.softwaremill.sttp.tapir" %% "tapir-core" % "1.11.20",
      "com.softwaremill.sttp.tapir" %% "tapir-apispec-docs" % "1.11.20",
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % "1.11.20",
      "com.softwaremill.sttp.apispec" %% "jsonschema-circe" % "0.11.7",
      
      // MCP SDK
      "io.modelcontextprotocol.sdk" % "mcp" % "0.8.1",

    ),
    // Set the main class for 'sbt run'
    Compile / run / mainClass := Some("fastmcp.FastMCPMain")
  )