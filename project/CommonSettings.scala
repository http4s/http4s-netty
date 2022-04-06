import sbt._, Keys._

object CommonSettings {
  val settings: Seq[Setting[_]] = Seq(
    startYear := Some(2020),
    Compile / compile / scalacOptions ++= Seq("-release", "8"),
    Test / scalacOptions ++= Seq("-release", "11"),
  )
}