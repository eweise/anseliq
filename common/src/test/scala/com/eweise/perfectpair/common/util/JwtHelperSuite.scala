package com.eweise.perfectpair.common.util

import org.scalatest.funsuite.AnyFunSuite
import com.eweise.perfectpair.common.util.JwtHelper.*
import com.eweise.perfectpair.common.util.JwtHelper

class JwtHelperSuite extends AnyFunSuite {
  test("issue and validate") {
    val secretKey     = "LXNEGEFEREE"
    val userClaim     = UserClaim("rabbit")
    val newToken      = JwtHelper.issueToken(userClaim, secretKey)
    val decodedResult = JwtHelper.decode(newToken, secretKey)
    assert(decodedResult.contains(userClaim))
  }
}
