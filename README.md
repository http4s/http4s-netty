# netty-http4s
![Build and Test](https://github.com/http4s/http4s-netty/workflows/Build%20and%20Test/badge.svg) [![Gitter chat](https://badges.gitter.im/http4s/http4s.png)](https://gitter.im/http4s/http4s) ![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.http4s/http4s-netty-core_2.13/badge.svg)


Untawing of [@jmcardon](https://github.com/jmcardon) 's branch from [1831](https://github.com/http4s/http4s/pull/1831) to attempt to get [Netty Reactive Streams](https://github.com/playframework/netty-reactive-streams) working in Http4s.

We are in every alpha quality here, and I would encourage people to try to use it and report bugs.

You can test it out by adding 

## Server

```scala
libraryDependencies += "org.http4s" %%  "http4s-netty-server" % "0.2.0"
```

## Client
```scala
libraryDependencies += "org.http4s" %%  "http4s-netty-client" % "0.2.0"
```

to your `build.sbt`


This project is lead by [@hamnis](https://github.com/hamnis) and is subject to the [Scala Code of Conduct](CODE_OF_CONDUCT.md)
