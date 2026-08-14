package onparity.service

import onparity.common.util.JwtHelper
import onparity.common.util.JwtHelper.UserClaim
import onparity.service.JWTIssuer.User

object NoZioJWTIssuer:
  private val secretKey = "123"

  def issueToken(user: User): String = JwtHelper.issueToken(UserClaim(user.username), secretKey)
