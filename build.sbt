organization := "net.hamnaberg.http4s"
name := "http4s-netty"

val http4sVersion = "0.21.4"
val netty         = "4.1.49.Final"

scalaVersion := "2.13.2"

libraryDependencies ++= List(
  "co.fs2"                 %% "fs2-reactive-streams"          % "2.3.0",
  "com.typesafe.netty"      % "netty-reactive-streams-http"   % "2.0.4" exclude ("io.netty", "netty-codec-http"),
  "io.netty"                % "netty-codec-http"              % netty,
  "io.netty"                % "netty-transport-native-epoll"  % netty classifier "linux-x86_64",
  "io.netty"                % "netty-transport-native-kqueue" % netty classifier "osx-x86_64",
  "org.http4s"             %% "http4s-server"                 % http4sVersion,
  "org.http4s"             %% "http4s-dsl"                    % http4sVersion % Test,
  "org.scalameta"          %% "munit"                         % "0.7.6"       % Test,
  "ch.qos.logback"          % "logback-classic"               % "1.2.3"       % Test,
  "org.scala-lang.modules" %% "scala-java8-compat"            % "0.9.1"       % Test
)

testFrameworks += new TestFramework("munit.Framework")
