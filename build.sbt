import com.typesafe.tools.mima.core._

val Scala213 = "2.13.13"

inThisBuild(
  Seq(
    Test / fork := true,
    developers := List(
      // your GitHub handle and name
      tlGitHubDev("hamnis", "Erlend Hamnaberg")
    ),
    licenses := Seq(License.Apache2),
    tlBaseVersion := "1.0",
    tlSonatypeUseLegacyHost := false,
    crossScalaVersions := Seq(Scala213, "3.3.3"),
    ThisBuild / scalaVersion := Scala213,
    githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
  )
)

val http4sVersion = "1.0.0-M41"

val jetty = "12.0.13"

val netty = "4.1.107.Final"

val munit = "0.7.29"

val io_uring = "0.0.25.Final"

val nativeNettyModules =
  Seq(
    "io.netty" % "netty-transport-classes-epoll" % netty,
    "io.netty" % "netty-transport-classes-kqueue" % netty,
    "io.netty.incubator" % "netty-incubator-transport-classes-io_uring" % io_uring,
    ("io.netty" % "netty-transport-native-epoll" % netty).classifier("linux-x86_64") % Runtime,
    ("io.netty" % "netty-transport-native-epoll" % netty).classifier("linux-aarch_64") % Runtime,
    ("io.netty" % "netty-transport-native-kqueue" % netty).classifier("osx-x86_64") % Runtime,
    ("io.netty" % "netty-transport-native-kqueue" % netty).classifier("osx-aarch_64") % Runtime,
    ("io.netty.incubator" % "netty-incubator-transport-native-io_uring" % io_uring)
      .classifier("linux-x86_64") % Runtime
  )

lazy val core = project
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-core",
    libraryDependencies ++= List(
      "org.log4s" %% "log4s" % "1.10.0",
      "co.fs2" %% "fs2-reactive-streams" % "3.9.4",
      ("org.playframework.netty" % "netty-reactive-streams-http" % "3.0.2")
        .exclude("io.netty", "netty-codec-http")
        .exclude("io.netty", "netty-handler"),
      "io.netty" % "netty-codec-http" % netty,
      "io.netty" % "netty-handler" % netty,
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.typelevel" %% "cats-effect" % "3.5.4"
    )
  )

lazy val server = project
  .dependsOn(core, client % "compile->test")
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-server",
    libraryDependencies ++= List(
      "io.netty" % "netty-codec-http2" % netty,
      "org.eclipse.jetty" % "jetty-client" % jetty % Test,
      "org.eclipse.jetty.http2" % "jetty-http2-client" % jetty % Test,
      "org.eclipse.jetty.http2" % "jetty-http2-client-transport" % jetty % Test,
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "ch.qos.logback" % "logback-classic" % "1.4.5" % Test,
      "org.scalameta" %% "munit" % munit % Test,
      "org.scalameta" %% "munit-scalacheck" % munit % Test,
      "org.http4s" %% "http4s-circe" % http4sVersion % Test,
      "org.http4s" %% "http4s-jdk-http-client" % "1.0.0-M9" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0-M4" % Test
    ),
    libraryDependencies ++= nativeNettyModules,
    mimaBinaryIssueFilters := Nil
  )

lazy val client = project
  .dependsOn(core)
  .settings(CommonSettings.settings)
  .settings(
    name := "http4s-netty-client",
    libraryDependencies ++= List(
      "org.http4s" %% "http4s-client" % http4sVersion,
      "io.netty" % "netty-codec-http2" % netty,
      "io.netty" % "netty-handler-proxy" % netty,
      "org.http4s" %% "http4s-client-testkit" % http4sVersion % Test,
      "org.http4s" %% "http4s-ember-server" % http4sVersion % Test,
      "org.http4s" %% "http4s-dsl" % http4sVersion % Test,
      "org.scala-lang.modules" %% "scala-java8-compat" % "1.0.2" % Test,
      ("com.github.monkeywie" % "proxyee" % "1.7.6" % Test)
        .excludeAll("io.netty")
        .excludeAll("org.bouncycastle"),
      "com.github.bbottema" % "java-socks-proxy-server" % "3.0.0" % Test,
      "org.scalameta" %% "munit" % munit % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.13" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0-M4" % Test
    ),
    libraryDependencies ++= nativeNettyModules
  )

lazy val root = tlCrossRootProject.aggregate(core, client, server)
