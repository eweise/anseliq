package com.eweise.perfectpair.service

import com.eweise.perfectpair.util.JwtHelper
import com.eweise.perfectpair.util.JwtHelper.UserClaim
import com.eweise.perfectpair.config.AppConfig
import com.eweise.perfectpair.service.JWTIssuer.User
import pdi.jwt.Jwt
import pdi.jwt.JwtAlgorithm
import pdi.jwt.JwtClaim
import zio.*
import zio.json.*
import zio.json.DeriveJsonCodec
import zio.json.JsonCodec

import java.time.Instant

import JWTVerifier.*

object NoZioJWTIssuer:
  private val secretKey = "123"

  def issueToken(user: User): String = JwtHelper.issueToken(UserClaim(user.username), secretKey)
