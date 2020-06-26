publishArtifact := false
releaseCrossBuild := true
releasePublishArtifactsAction := PgpKeys.publishSigned.value

inThisBuild(
  Seq(
    publishTo := {
      if (version.value.endsWith("SNAPSHOT"))
        Some(Opts.resolver.sonatypeSnapshots)
      else
        Some(Opts.resolver.sonatypeStaging)
    },
    packageOptions += {
      val title = name.value
      val ver = version.value
      val vendor = organization.value

      Package.ManifestAttributes(
        "Created-By" -> "Scala Build Tool",
        "Built-By" -> System.getProperty("user.name"),
        "Build-Jdk" -> System.getProperty("java.version"),
        "Specification-Title" -> title,
        "Specification-Version" -> ver,
        "Specification-Vendor" -> vendor,
        "Implementation-Title" -> title,
        "Implementation-Version" -> ver,
        "Implementation-Vendor-Id" -> vendor,
        "Implementation-Vendor" -> vendor
      )
    },
    credentials ++= Seq(
      Credentials(Path.userHome / ".sbt" / ".credentials")
    ),
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ =>
      false
    },
    homepage := Some(url("https://github.com/http4s/http4s-netty")),
    startYear := Some(2020),
    licenses := Seq(
      "Apache2" -> url("https://github.com/http4s/http4s-netty/blob/master/LICENSE")
    ),
    scmInfo := Some(
      ScmInfo(
        new URL("https://github.com/http4s/http4s-netty"),
        "scm:git:git@github.com:http4s/http4s-netty.git",
        Some("scm:git:git@github.com:http4s/http4s-netty.git")
      )),
    developers ++= List(
      Developer(
        "hamnis",
        "Erlend Hamnaberg",
        "erlend@hamnaberg.net",
        new URL("http://twitter.com/hamnis")
      )
    )
  )
)
