package com.eweise.perfectpair.service

import com.eweise.perfectpair.util.JwtHelper
import com.eweise.perfectpair.util.JwtHelper.UserClaim
import com.eweise.perfectpair.config.AppConfig
import zio.*
import JWTVerifier.*
import com.eweise.perfectpair.util.JwtHelper.UserClaim

trait NoZioJWTVerifier {
  def isValid(token: String): Boolean = decode(token).fold(_ => false, _ => true)
  def decode(token: String): Either[String, UserClaim]
}

object NoZioJWTVerifier {
  val secretKey = "123"
  def apply():NoZioJWTVerifier = 
    new NoZioJWTVerifier {
      override def decode(token: String): Either[String, UserClaim] =
        JwtHelper.decode(token, secretKey)
    }
//  def decode(token: String): ZIO[JWTVerifier, String, UserClaim] =
//    ZIO.serviceWithZIO[JWTVerifier](_.decode(token))

}
