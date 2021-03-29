inThisBuild(
  Seq(
    organization := "org.http4s",
    crossScalaVersions := Seq("2.13.5", "2.12.13"),
    scalaVersion := crossScalaVersions.value.head,
    testFrameworks += new TestFramework("munit.Framework"),
    addCompilerPlugin(("org.typelevel" % "kind-projector" % "0.11.3").cross(CrossVersion.full)),
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    fork in Test := true
  )
)

val http4sVersion = "0.21.20"


val netty = "4.1.60.Final"
val munit = "0.7.22"

lazy val core = project
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-core",
    libraryDependencies ++= List(
      "co.fs2" %% "fs2-reactive-streams" % "3.0.0",
      ("com.typesafe.netty" % "netty-reactive-streams-http" % "2.0.5")
        .exclude("io.netty", "netty-codec-http")
        .exclude("io.netty", "netty-handler"),
      "io.netty" % "netty-codec-http" % netty,
      ("io.netty" % "netty-transport-native-epoll" % netty).classifier("linux-x86_64"),
      ("io.netty" % "netty-transport-native-kqueue" % netty).classifier("osx-x86_64"),
      "org.http4s" %% "http4s-core" % http4sVersion
    )
  )

lazy val server = project
  .dependsOn(core, client % Test)
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-server",
    libraryDependencies ++= List(
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.3" % Test,
      "org.scalameta" %% "munit" % munit % Test,
      "org.scalameta" %% "munit-scalacheck" % munit % Test,
      "org.http4s" %% "http4s-circe" % http4sVersion % Test,
      "org.http4s" %% "http4s-jdk-http-client" % "0.3.6" % Test,
      "org.http4s" %% "http4s-blaze-client" % http4sVersion % Test
    )
  )

lazy val client = project
  .dependsOn(core)
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-client",
    libraryDependencies ++= List(
      "org.http4s" %% "http4s-client" % http4sVersion,
      "org.scalameta" %% "munit" % munit % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.3" % Test,
      "org.gaul" % "httpbin" % "1.3.0" % Test
    )
  )

lazy val root =
  project
    .in(file("."))
    .settings(CommonSettings.settings)
    .settings(
      name := "http4s-netty",
      publishArtifact := false,
      releaseCrossBuild := true,
      releaseVersionBump := sbtrelease.Version.Bump.Minor
    )
    .aggregate(core, client, server)
