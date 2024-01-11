import sbt._, Keys._

object CommonSettings {
  val settings: Seq[Setting[_]] = Seq(
    startYear := Some(2020),
    scalacOptions -= "-java-output-version",
    Compile / compile / scalacOptions ++= Seq("-release", "8"),
    Test / compile / scalacOptions ++= Seq("-release", "11")
  )
}
