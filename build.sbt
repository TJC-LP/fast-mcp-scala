ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.6.4" // Using Scala 3

val zioVersion = "2.1.17" // Specify ZIO version
val zioSchemaVersion = "1.6.6" // ZIO Schema version
val jacksonVersion = "2.18.3"

lazy val root = (project in file("."))
  .settings(
    name := "fast-mcp-scala",
    // Enable Scala 3 macros
    resolvers += Resolver.mavenLocal,
    scalacOptions ++= Seq("-Xcheck-macros", "-experimental", "-Xmax-inlines:100000"),
    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-json" % "0.7.42",
      
      // ZIO Schema for schema generation
      "dev.zio" %% "zio-schema" % zioSchemaVersion,
      "dev.zio" %% "zio-schema-json" % zioSchemaVersion,
      "dev.zio" %% "zio-schema-derivation" % zioSchemaVersion,
      
      // Jackson for JSON serialization/deserialization
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,

      // Tapir
      "com.softwaremill.sttp.tapir" %% "tapir-core" % "1.11.24",
      "com.softwaremill.sttp.tapir" %% "tapir-apispec-docs" % "1.11.24",
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % "1.11.24",
      "com.softwaremill.sttp.apispec" %% "jsonschema-circe" % "0.11.8",
      
      // MCP SDK
      "io.modelcontextprotocol.sdk" % "mcp" % "0.10.0-SNAPSHOT",
      
      // Test dependencies
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test,
    ),
    // Set the main class for 'sbt run'
    Compile / run / mainClass := Some("fastmcp.FastMCPMain"),
    
    // Configure test class loading to fix enum reflection issues
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )