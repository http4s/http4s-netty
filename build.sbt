import com.typesafe.tools.mima.core._

val Scala212 = "2.12.20"
val Scala213 = "2.13.16"

inThisBuild(
  Seq(
    startYear := Some(2020),
    Test / fork := true,
    developers := List(
      // your GitHub handle and name
      tlGitHubDev("hamnis", "Erlend Hamnaberg")
    ),
    licenses := Seq(License.Apache2),
    tlBaseVersion := "0.7",
    crossScalaVersions := Seq(Scala213, Scala212, "3.3.6"),
    ThisBuild / scalaVersion := Scala213,
    tlJdkRelease := Some(11),
    githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
  )
)

val http4sVersion = "0.23.30"

val jetty = "12.0.25"

val netty = "4.2.4.Final"

val munit = "1.1.1"
val munitScalaCheck = "1.1.0"

val io_uring = "0.0.26.Final"

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
      "co.fs2" %% "fs2-core" % "3.12.0",
      "org.reactivestreams" % "reactive-streams-flow-adapters" % "1.0.2",
      ("org.playframework.netty" % "netty-reactive-streams-http" % "3.0.4")
        .exclude("io.netty", "netty-codec-http")
        .exclude("io.netty", "netty-handler"),
      "io.netty" % "netty-codec-http" % netty,
      "io.netty" % "netty-handler" % netty,
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.typelevel" %% "cats-effect" % "3.6.3"
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
      "ch.qos.logback" % "logback-classic" % "1.2.13" % Test,
      "org.scalameta" %% "munit" % munit % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalaCheck % Test,
      "org.http4s" %% "http4s-circe" % http4sVersion % Test,
      "org.http4s" %% "http4s-jdk-http-client" % "0.10.0" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.1.0" % Test
    ),
    libraryDependencySchemes += "org.typelevel" %% "munit-cats-effect" % VersionScheme.Always, // "early-semver",
    libraryDependencies ++= nativeNettyModules,
    mimaBinaryIssueFilters ++= Nil
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
      ("com.github.bbottema" % "java-socks-proxy-server" % "4.1.2" % Test)
        .exclude("org.slf4j", "slf4j-api"),
      "org.scalameta" %% "munit" % munit % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.13" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.1.0" % Test
    ),
    libraryDependencySchemes += "org.typelevel" %% "munit-cats-effect" % VersionScheme.Always, // "early-semver",
    libraryDependencies ++= nativeNettyModules,
    mimaBinaryIssueFilters ++= Nil
  )

lazy val root = tlCrossRootProject.aggregate(core, client, server)
