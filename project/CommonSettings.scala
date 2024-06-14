import sbt._, Keys._

object CommonSettings {
  val settings: Seq[Setting[_]] = Seq(
    startYear := Some(2020)
  )
}
