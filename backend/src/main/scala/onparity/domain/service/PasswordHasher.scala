package onparity.domain.service

import at.favre.lib.crypto.bcrypt.BCrypt

// Pure, stateless bcrypt wrapper shared by AuthService (verify on login) and UserService (hash on
// create/reset) — bcrypt (not Argon2) chosen specifically for this app's on-prem-at-fintechs story:
// it's pure-JVM with no native/JNI library, avoiding platform-compatibility risk across whatever
// OS/JVM combo a given fintech runs, at the cost of not being OWASP's top-ranked KDF pick.
object PasswordHasher:
  private val cost = 12

  def hash(password: String): String =
    BCrypt.withDefaults().hashToString(cost, password.toCharArray)

  def verify(password: String, hash: String): Boolean =
    BCrypt.verifyer().verify(password.toCharArray, hash).verified
