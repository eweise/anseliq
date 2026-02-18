package perfectpair.service

import perfectpair.common.util.JwtHelper
import perfectpair.common.util.JwtHelper.UserClaim
import perfectpair.config.AppConfig
import perfectpair.service.JWTVerifier.*
import zio.*

trait JWTVerifier {
  def isValid(token: String): UIO[Boolean] = decode(token).fold(_ => false, _ => true)
  def decode(token:  String): IO[String, UserClaim]
}

object JWTVerifier {
  val live = ZLayer.fromFunction((config: AppConfig) =>
    new JWTVerifier {
      private val secret = config.secret
      override def decode(token: String): IO[String, UserClaim] =
        ZIO.fromEither(JwtHelper.decode(token, secret))
    }
  )

  def decode(token: String): ZIO[JWTVerifier, String, UserClaim] =
    ZIO.serviceWithZIO[JWTVerifier](_.decode(token))

}
