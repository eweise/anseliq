package perfectpair.service

import perfectpair.common.util.JwtHelper
import perfectpair.common.util.JwtHelper.UserClaim
import perfectpair.service.JWTIssuer.User

object NoZioJWTIssuer:
  private val secretKey = "123"

  def issueToken(user: User): String = JwtHelper.issueToken(UserClaim(user.username), secretKey)
