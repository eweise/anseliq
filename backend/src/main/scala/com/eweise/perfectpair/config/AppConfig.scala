package com.eweise.perfectpair.config

import zio.*

case class AppConfig(secret: String)

object AppConfig {

  val live: ULayer[AppConfig] = ZLayer.succeed(AppConfig("t0ps3cret-token-change-me-in-prod"))
}
