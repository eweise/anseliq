package onparity.domain.service

import onparity.common.util.JwtHelper
import onparity.common.util.JwtHelper.UserClaim
import onparity.domain.port.driven.{Txn, UserRepo}

// Wraps the raw secret string in its own type (rather than a bare `given String`) so it can be
// wired as a `using` dependency the same way every other service in this app is, without risking
// ambiguity against some other, unrelated `given String` a future feature might introduce.
case class JwtSecret(value: String)

class AuthService(using userRepo: UserRepo, jwtSecret: JwtSecret):

  // Same generic failure message whether the email doesn't exist, the account is deactivated, or
  // the password is wrong — deliberately not distinguishing which, so a failed login doesn't leak
  // which emails have accounts in this system.
  def login(email: String, password: String): Txn[Either[String, String]] =
    userRepo.findByEmail(email.trim.toLowerCase) match
      case Some(user) if user.active && PasswordHasher.verify(password, user.passwordHash) =>
        val claim = UserClaim(userId = user.id.toString, email = user.email, role = user.role.toString)
        Right(JwtHelper.issueToken(claim, jwtSecret.value))
      case _ =>
        Left("Invalid email or password")
