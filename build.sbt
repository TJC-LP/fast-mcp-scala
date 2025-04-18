ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.6.4" // Using Scala 3
ThisBuild / versionScheme := Some("semver-spec")

lazy val Versions = new {
  val zio = "2.1.17"
  val zioSchema = "1.6.6"
  val jackson = "2.18.3"
  val tapir = "1.11.25"
  val jsonSchemaCirce = "0.11.9"
  val mcpSdk = "0.9.0"
  val scalaTest = "3.2.19"
}

ThisBuild / scalacOptions ++= Seq(
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wsafe-init", // detect uninitialized vals
  "-Wnonunit-statement"
)
// remove only the discard/statement warnings in tests
Test / scalacOptions --= Seq("-Wvalue-discard", "-Wnonunit-statement")

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
    Test / semanticdbEnabled := true,
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
      // ScalaCheck support for property-based testing
      "org.scalacheck" %% "scalacheck" % "1.18.1" % Test,
      // Mockito for mocking in tests
      "org.scalatestplus" %% "mockito-5-12" % "3.2.19.0" % Test,
      "dev.zio" %% "zio-test" % Versions.zio % Test,
      "dev.zio" %% "zio-test-sbt" % Versions.zio % Test,
      "dev.zio" %% "zio-test-magnolia" % Versions.zio % Test
    ),
    // Set the main class for 'sbt run'
    Compile / run / mainClass := Some("fastmcp.FastMCPMain"),

    // Configure test class loading to fix enum reflection issues
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )

// -----------------------------------------------------------------------------
// CI / CD configuration driven by sbt‑github‑actions and sbt‑ci‑release
// -----------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// General build matrix settings
// ---------------------------------------------------------------------------

ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("17"),
  JavaSpec.temurin("21"),
  JavaSpec.temurin("24"),
)

ThisBuild / githubWorkflowScalaVersions := Seq("3.6.4")

// Only run on main branch and version tags (vX.Y.Z)
ThisBuild / githubWorkflowTargetBranches := Seq(
  "main"
)

ThisBuild / githubWorkflowTargetTags := Seq("v*")

// Workflow drift check disabled for now to avoid false positives during initial migration
// ThisBuild / githubWorkflowCheck := true

// ---------------------------------------------------------------------------
// Quality gates: formatting, linting, coverage, etc.
// ---------------------------------------------------------------------------

ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Sbt(List("scalafmtCheckAll", "scalafixAll"), name = Some("Scalafmt & Scalafix")),
  WorkflowStep.Sbt(List("coverage", "test", "coverageAggregate"), name = Some("Tests & Coverage")),
  WorkflowStep.Run(
    List("bash", "-lc", "bash <(curl -s https://codecov.io/bash)"),
    name = Some("Upload coverage to Codecov"),
    cond = Some("success()")
  )
)

    // Fail build if coverage below minimum threshold (initially 60%, will be raised gradually)
coverageMinimumStmtTotal := 60
coverageFailOnMinimum := true

coverageEnabled := true
// Enable branch coverage highlighting and Cobertura output
coverageHighlighting := true
coverageOutputCobertura := true

// ---------------------------------------------------------------------------
// Snapshot & release publishing (sbt-ci-release)
// ---------------------------------------------------------------------------

inThisBuild(
  Seq(
    organization := "com.tjclp",
    homepage := Some(url("https://tjclp.com")),
    licenses := List("MIT" -> url("https://opensource.org/licenses/MIT")),
    developers := List(
      Developer(
        "arcaputo3",
        "Richie Caputo",
        "rcaputo3@tjclp.com",
        url("https://tjclp.com")
      )
    ),
    sonatypeCredentialHost := "s01.oss.sonatype.org"
  )
)

ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")))
ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    commands = List("ci-release"),
    name = Some("Publish project"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  )
)

// ---------------------------------------------------------------------------
// Scaladoc / site generation (gh-pages) — executed only on release tags
// ---------------------------------------------------------------------------
