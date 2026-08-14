package onparity.service

import onparity.common.util.JwtHelper
import onparity.common.util.JwtHelper.UserClaim
import onparity.config.AppConfig
import onparity.service.JWTVerifier.*
import zio.*

trait NoZioJWTVerifier {
  def isValid(token: String): Boolean = decode(token).fold(_ => false, _ => true)
  def decode(token:  String): Either[String, UserClaim]
}

object NoZioJWTVerifier {
  val secretKey = "123"
  def apply(): NoZioJWTVerifier =
    new NoZioJWTVerifier {
      override def decode(token: String): Either[String, UserClaim] =
        JwtHelper.decode(token, secretKey)
    }
//  def decode(token: String): ZIO[JWTVerifier, String, UserClaim] =
//    ZIO.serviceWithZIO[JWTVerifier](_.decode(token))

}
