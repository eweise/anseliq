package com.eweise.perfectpair.service

import com.eweise.perfectpair.common.util.JwtHelper
import com.eweise.perfectpair.service.JWTIssuer.User
import JwtHelper.UserClaim

object NoZioJWTIssuer:
  private val secretKey = "123"

  def issueToken(user: User): String = JwtHelper.issueToken(UserClaim(user.username), secretKey)
