inThisBuild(
  Seq(
    organization := "org.http4s",
    name := "http4s-netty",
    crossScalaVersions := Seq("2.13.2", "2.12.11"),
    scalaVersion := crossScalaVersions.value.head,
  )
)
Compile/scalacOptions ++= Seq("-release", "8")
Test/scalacOptions ++= Seq("-release", "11")

val http4sVersion = "0.21.4"
val netty         = "4.1.49.Final"
val munit         = "0.7.6"

libraryDependencies ++= List(
  "co.fs2"                 %% "fs2-reactive-streams"          % "2.3.0",
  "com.typesafe.netty"      % "netty-reactive-streams-http"   % "2.0.4" exclude ("io.netty", "netty-codec-http") exclude ("io.netty", "netty-handler"),
  "io.netty"                % "netty-codec-http"              % netty,
  "io.netty"                % "netty-transport-native-epoll"  % netty classifier "linux-x86_64",
  "io.netty"                % "netty-transport-native-kqueue" % netty classifier "osx-x86_64",
  "org.http4s"             %% "http4s-server"                 % http4sVersion,
  "org.http4s"             %% "http4s-dsl"                    % http4sVersion % Test,
  "ch.qos.logback"          % "logback-classic"               % "1.2.3"       % Test,
  "org.scalameta"          %% "munit"                         % munit         % Test,
  "org.scalameta"          %% "munit-scalacheck"              % munit         % Test
)

testFrameworks += new TestFramework("munit.Framework")
