package org.http4s.netty

import scala.compat.java8.FutureConverters._
import java.util.concurrent.CompletableFuture

abstract class NettySuite extends IOSuite {
  override def munitValueTransforms: List[ValueTransform] = {
    new ValueTransform(
      "completable future",
      {
        case cf: CompletableFuture[_] => cf.toScala
      }
    ) :: super.munitValueTransforms
  }

}
