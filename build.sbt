import com.typesafe.tools.mima.core._

val Scala212 = "2.12.17"
val Scala213 = "2.13.10"

inThisBuild(
  Seq(
    Test / fork := true,
    developers := List(
      // your GitHub handle and name
      tlGitHubDev("hamnis", "Erlend Hamnaberg")
    ),
    licenses := Seq(License.Apache2),
    tlBaseVersion := "0.5",
    tlSonatypeUseLegacyHost := false,
    crossScalaVersions := Seq(Scala213, Scala212, "3.2.1"),
    ThisBuild / scalaVersion := Scala213,
    githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))
  )
)

val http4sVersion = "0.23.16"

val netty = "4.1.85.Final"

val munit = "0.7.29"

val io_uring = "0.0.16.Final"

val nativeNettyModules =
  Seq(
    "io.netty" % "netty-transport-classes-epoll" % netty,
    "io.netty" % "netty-transport-classes-kqueue" % netty,
    "io.netty.incubator" % "netty-incubator-transport-classes-io_uring" % io_uring,
    ("io.netty" % "netty-transport-native-epoll" % netty).classifier("linux-x86_64") % Runtime,
    ("io.netty" % "netty-transport-native-kqueue" % netty).classifier("osx-x86_64") % Runtime,
    ("io.netty.incubator" % "netty-incubator-transport-native-io_uring" % io_uring)
      .classifier("linux-x86_64") % Runtime
  )

lazy val core = project
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-core",
    libraryDependencies ++= List(
      "co.fs2" %% "fs2-reactive-streams" % "3.3.0",
      ("com.typesafe.netty" % "netty-reactive-streams-http" % "2.0.7")
        .exclude("io.netty", "netty-codec-http")
        .exclude("io.netty", "netty-handler"),
      "io.netty" % "netty-codec-http" % netty,
      "org.http4s" %% "http4s-core" % http4sVersion
    )
  )

lazy val server = project
  .dependsOn(core, client % Test)
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-server",
    libraryDependencies ++= List(
      "io.netty" % "netty-codec-http2" % netty,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.11" % Test,
      "org.scalameta" %% "munit" % munit % Test,
      "org.scalameta" %% "munit-scalacheck" % munit % Test,
      "org.http4s" %% "http4s-circe" % http4sVersion % Test,
      "org.http4s" %% "http4s-jdk-http-client" % "0.7.0" % Test,
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test
    ),
    libraryDependencies ++= nativeNettyModules
  )

lazy val client = project
  .dependsOn(core)
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-client",
    libraryDependencies ++= List(
      "org.http4s" %% "http4s-client" % http4sVersion,
      "io.netty" % "netty-handler-proxy" % netty,
      "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2" % Test,
      "com.github.monkeywie" % "proxyee" % "1.7.0" % Test,
      "com.github.bbottema" % "java-socks-proxy-server" % "2.0.0" % Test,
      "org.scalameta" %% "munit" % munit % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.11" % Test,
      "org.gaul" % "httpbin" % "1.3.0" % Test,
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test
    ),
    libraryDependencies ++= nativeNettyModules,
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[Problem]("org.http4s.netty.client.Http4sChannelPoolMap"),
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.netty.client.NettyClientBuilder$EventLoopHolder$"),
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.netty.client.NettyClientBuilder$SSLContextOption*"),
      ProblemFilters.exclude[MissingClassProblem](
        "org.http4s.netty.client.NettyClientBuilder$EventLoopHolder"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "org.http4s.netty.client.Http4sChannelPoolMap#Config.sslConfig"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "org.http4s.netty.client.Http4sChannelPoolMap#Config.copy$default$7"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "org.http4s.netty.client.Http4sChannelPoolMap#Config.*"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.netty.client.Http4sChannelPoolMap#Config.copy"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.netty.client.Http4sChannelPoolMap#Config.apply"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.netty.client.Http4sChannelPoolMap#Config.this"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "org.http4s.netty.client.NettyClientBuilder.this"),
      ProblemFilters.exclude[MissingFieldProblem](
        "org.http4s.netty.client.NettyClientBuilder.SSLContextOption")
    )
  )

lazy val root = tlCrossRootProject.aggregate(core, client, server)
