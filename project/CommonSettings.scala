import io.github.davidgregory084.TpolecatPlugin.autoImport._
import com.jsuereth.sbtpgp.PgpKeys
import sbtrelease.ReleasePlugin.autoImport._
import sbt._, Keys._

object CommonSettings {
  val settings = Seq(
    organization := "org.http4s",
    crossScalaVersions := Seq("2.13.8", "3.1.1"),
    scalaVersion := crossScalaVersions.value.head,
    libraryDependencies ++= {
      if (scalaBinaryVersion.value.startsWith("2")) {
        Seq(
          compilerPlugin(("org.typelevel" % "kind-projector" % "0.13.2").cross(CrossVersion.full)),
          compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
        )
      } else Nil
    },
    Compile / compile / scalacOptions ++= Seq("-release", "8"),
    Compile / compile / scalacOptions --= ScalacOptions.fatalWarnings.tokens,
    Test / scalacOptions ++= Seq("-release", "11"),
    Test / scalacOptions --= ScalacOptions.fatalWarnings.tokens,
    publishTo := {
      if (isSnapshot.value)
        Some(Opts.resolver.sonatypeSnapshots)
      else
        Some(Opts.resolver.sonatypeStaging)
    },
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
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
    Test / publishArtifact := false,
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
}
