package com.eweise.perfectpair.service

import com.eweise.perfectpair.service.JWTIssuer.User
import com.eweise.perfectpair.util.JwtHelper
import com.eweise.perfectpair.util.JwtHelper.UserClaim

object NoZioJWTIssuer:
  private val secretKey = "123"

  def issueToken(user: User): String = JwtHelper.issueToken(UserClaim(user.username), secretKey)
