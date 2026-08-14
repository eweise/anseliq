package onparity.service

import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import onparity.common.util.JwtHelper
import onparity.common.util.JwtHelper.UserClaim
import onparity.config.AppConfig
import onparity.service.JWTIssuer.User
import onparity.service.JWTVerifier.*
import zio.*
import zio.json.*

import java.time.Instant

trait JWTIssuer {
  def issueToken(user: User): UIO[String]
}

object JWTIssuer {
  val live = ZLayer.fromFunction((config: AppConfig) =>
    new JWTIssuer {
      private val secretKey = config.secret
      override def issueToken(user: User): UIO[String] =
        val claim = UserClaim(user.username)
        ZIO.succeed(JwtHelper.issueToken(claim, secretKey))
    }
  )

  def issueToken(user: User): URIO[JWTIssuer, String] = ZIO.serviceWithZIO[JWTIssuer](_.issueToken(user))

  case class User(username: String)
}
