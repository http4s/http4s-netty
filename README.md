# netty-http4s
[![Continuous Integration](https://github.com/http4s/http4s-netty/actions/workflows/ci.yml/badge.svg)](https://github.com/http4s/http4s-netty/actions/workflows/ci.yml) ![Maven Central](https://img.shields.io/maven-central/v/org.http4s/http4s-netty-core_2.13?style=flat&versionPrefix=0.5)

Unfreeze of [@jmcardon](https://github.com/jmcardon) branch from [1831](https://github.com/http4s/http4s/pull/1831) to attempt to get [Netty Reactive Streams](https://github.com/playframework/netty-reactive-streams) working in Http4s.

This branch targets `http4s-1.0`. This currently only has milestone releases.
For a more supported version go to `series/0.4` or `series/0.5`

You can test it out by adding 

### Server

```scala
libraryDependencies += "org.http4s" %%  "http4s-netty-server" % "versionFromBadge"
```

### Client
```scala
libraryDependencies += "org.http4s" %%  "http4s-netty-client" % "versionFromBadge"
```

to your `build.sbt`


This project is lead by [@hamnis](https://github.com/hamnis) and is subject to the [Scala Code of Conduct](CODE_OF_CONDUCT.md)
