package perfectpair.service

import JWTIssuer.User
import perfectpair.common.util.JwtHelper
import JwtHelper.UserClaim

object NoZioJWTIssuer:
  private val secretKey = "123"

  def issueToken(user: User): String = JwtHelper.issueToken(UserClaim(user.username), secretKey)
