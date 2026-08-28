package onparity.common.util

import org.scalatest.funsuite.AnyFunSuite
import onparity.common.util.JwtHelper.*

class JwtHelperSuite extends AnyFunSuite {
  test("issue and validate") {
    val secretKey     = "LXNEGEFEREE"
    val userClaim     = UserClaim("00000000-0000-0000-0000-000000000001", "rabbit@example.com", "Admin")
    val newToken      = JwtHelper.issueToken(userClaim, secretKey)
    val decodedResult = JwtHelper.decode(newToken, secretKey)
    assert(decodedResult.contains(userClaim))
  }

  test("decode fails with the wrong secret key") {
    val userClaim = UserClaim("00000000-0000-0000-0000-000000000001", "rabbit@example.com", "Admin")
    val newToken  = JwtHelper.issueToken(userClaim, "correct-secret")
    assert(JwtHelper.decode(newToken, "wrong-secret").isLeft)
  }

  test("getUnverifiedUserClaim extracts userId/email/role without checking the signature") {
    val userClaim = UserClaim("00000000-0000-0000-0000-000000000001", "rabbit@example.com", "Admin")
    val newToken  = JwtHelper.issueToken(userClaim, "any-secret-not-needed-here")
    assertResult(Right(userClaim))(JwtHelper.getUnverifiedUserClaim(newToken))
  }
}
