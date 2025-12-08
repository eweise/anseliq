package com.eweise.perfectpair.service

import com.eweise.perfectpair.common.util.JwtHelper
import com.eweise.perfectpair.config.AppConfig
import com.eweise.perfectpair.service.JWTIssuer.User
import com.eweise.perfectpair.service.JWTVerifier.*
import JwtHelper.UserClaim
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import zio.*
import zio.json.*

import java.time.Instant

trait JWTIssuer {
  def issueToken(user: User): UIO[String]
}

object JWTIssuer {
  def issueToken(user: User): URIO[JWTIssuer, String] = ZIO.serviceWithZIO[JWTIssuer](_.issueToken(user))

  case class User(username: String)

  val live = ZLayer.fromFunction((config: AppConfig) =>
    new JWTIssuer {
      private val secretKey = config.secret
      override def issueToken(user: User): UIO[String] =
        val claim = UserClaim(user.username)
        ZIO.succeed(JwtHelper.issueToken(claim, secretKey))
    }
  )
}
