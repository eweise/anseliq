package anseliq.common.util

import pdi.jwt.{Jwt, JwtAlgorithm, JwtBase64, JwtClaim}
import upickle.default.*

import java.time.Instant
import scala.util.Try

/**
 *   - Centralized JWT protocol setup for both Backend and Frontend.
 *   - A single source of truth for updating the JWT algorithm, JWT content ensuring consistency
 *     across the system.
 */
object JwtHelper:

  private val ALG = JwtAlgorithm.HS256

  def decode(accessToken: String, secretKey: String): Either[String, UserClaim] =
    Jwt
      .decode(accessToken, secretKey, List(ALG))
      .map(_.content)
      .fold[Either[String, String]](e => Left(s"Invalid token: ${e.getMessage()}"), Right(_))
      .flatMap(readClaim)

  def getUnverifiedUserClaim(accessToken: String): Either[String, UserClaim] =
    Try(new String(JwtBase64.decode(accessToken.split("\\.")(1)))).toEither.left
      .map(_ => "Invalid access token payload")
      .flatMap(readClaim)

  // The payload also holds the token's iat and exp, which reading a UserClaim ignores.
  private def readClaim(json: String): Either[String, UserClaim] =
    Try(read[UserClaim](json)).toEither.left.map(_ => "Invalid access token payload")

  // Workday-length session: this app has no refresh-token flow implemented, so a short expiration
  // (the previous default was 300 seconds) would make the app unusable — the user would be bounced
  // to /login mid-task with no way to silently renew. Revisit once refresh rotation exists.
  private val expirationSeconds = 12 * 60 * 60

  def issueToken(user: UserClaim, secretKey: String): String = {
    val content    = write(user)
    val issuedAt   = Instant.now.getEpochSecond
    val expiration = Instant.now.plusSeconds(expirationSeconds).getEpochSecond
    val claim      = JwtClaim(content, issuedAt = Some(issuedAt), expiration = Some(expiration))
    Jwt.encode(claim, secretKey, ALG)
  }

  case class UserClaim(userId: String, email: String, role: String) derives ReadWriter
