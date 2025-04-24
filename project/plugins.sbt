addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.2")

// CI/CD orchestration via sbt
addSbtPlugin("com.github.sbt" % "sbt-github-actions" % "0.25.0")

// Automated snapshot and release publishing to Maven Central
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.3")

// Code‑coverage support
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.3.1")

// .env support
addSbtPlugin("nl.gn0s1s" % "sbt-dotenv" % "3.1.1")