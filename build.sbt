ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.6.4" // Using Scala 3
ThisBuild / versionScheme := Some("semver-spec")

lazy val Versions = new {
  val zio = "2.1.17"
  val zioSchema = "1.6.6"
  val jackson = "2.18.3"
  val tapir = "1.11.24"
  val jsonSchemaCirce = "0.11.8"
  val mcpSdk = "0.9.0"
  val scalaTest = "3.2.19"
}

ThisBuild / scalacOptions ++= Seq(
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wsafe-init", // detect uninitialized vals
  "-Wnonunit-statement"
)

lazy val root = (project in file("."))
  .settings(
    name := "fast-mcp-scala",
    // Enable Scala 3 macros with reasonable inline limits for better compilation performance
    // resolvers += Resolver.mavenLocal,
    scalacOptions ++= Seq("-Xcheck-macros", "-experimental", "-Xmax-inlines:128"),
    Compile / scalafix / semanticdbEnabled := true,
    Compile / scalafix / scalafixOnCompile := true,
    ThisBuild / scalafmtOnCompile := true,
    semanticdbEnabled := true,
    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio" % Versions.zio,
      "dev.zio" %% "zio-json" % "0.7.42",

      // ZIO Schema for schema generation
      "dev.zio" %% "zio-schema" % Versions.zioSchema,
      "dev.zio" %% "zio-schema-json" % Versions.zioSchema,
      "dev.zio" %% "zio-schema-derivation" % Versions.zioSchema,

      // Jackson for JSON serialization/deserialization
      "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jackson,
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % Versions.jackson,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % Versions.jackson,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % Versions.jackson,

      // Tapir
      "com.softwaremill.sttp.tapir" %% "tapir-core" % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-apispec-docs" % Versions.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % Versions.tapir,
      "com.softwaremill.sttp.apispec" %% "jsonschema-circe" % Versions.jsonSchemaCirce,

      // MCP SDK
      "io.modelcontextprotocol.sdk" % "mcp" % Versions.mcpSdk,

      // Test dependencies
      "org.scalatest" %% "scalatest" % Versions.scalaTest % Test,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % Versions.zio % Test
    ),
    // Set the main class for 'sbt run'
    Compile / run / mainClass := Some("fastmcp.FastMCPMain"),

    // Configure test class loading to fix enum reflection issues
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )
