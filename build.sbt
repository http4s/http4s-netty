name := "http4s-netty"

val http4sVersion = "0.21.4"
val netty         = "4.1.49.Final"

libraryDependencies += "co.fs2"             %% "fs2-reactive-streams"         % "2.3.0"
libraryDependencies += "com.typesafe.netty" % "netty-reactive-streams-http"   % "2.0.4" exclude ("io.netty", "netty-codec-http")
libraryDependencies += "io.netty"           % "netty-codec-http"              % netty
libraryDependencies += "io.netty"           % "netty-transport-native-epoll"  % netty classifier "linux-x86_64"
libraryDependencies += "io.netty"           % "netty-transport-native-kqueue" % netty classifier "osx-x86_64"
libraryDependencies += "ch.qos.logback"     % "logback-classic"               % "1.2.3" % Test
libraryDependencies += "org.http4s"         %% "http4s-server"                % http4sVersion
libraryDependencies += "org.http4s"         %% "http4s-dsl"                   % http4sVersion % Test
