package onparity.common.util

import pdi.jwt.{Jwt, JwtAlgorithm, JwtBase64, JwtClaim}
import zio.json.*
import zio.json.ast.Json

import java.time.Instant

/**
 *   - Centralized JWT protocol setup for both Backend and Frontend.
 *   - A single source of truth for updating the JWT algorithm, JWT content ensuring consistency
 *     across the system.
 */
object JwtHelper:

  private val ALG = JwtAlgorithm.HS256 // can be udpated quickly

  given JsonCodec[UserClaim] = DeriveJsonCodec.gen[UserClaim]

  def decode(accessToken: String, secretKey: String): Either[String, UserClaim] =
    Jwt
      .decode(accessToken, secretKey, List(ALG))
      .map(_.content)
      .fold[Either[String, String]](e => Left(s"Invalid token: ${e.getMessage()}"), Right(_))
      .flatMap(_.fromJson[UserClaim])

  def getUnverifiedUserClaim(accessToken: String): Either[String, UserClaim] =
    val payload     = new String(JwtBase64.decode(accessToken.split("\\.")(1)))
    val jsonPayload = payload.fromJson[Json.Obj].getOrElse(Json.Obj())
    (for {
      userId <- jsonPayload.get("userId").flatMap(_.asString)
      email  <- jsonPayload.get("email").flatMap(_.asString)
      role   <- jsonPayload.get("role").flatMap(_.asString)
    } yield UserClaim(userId, email, role)) match
      case None =>
        Left("Invalid access token payload")
      case Some(claim) =>
        Right(claim)

  // Workday-length session: this app has no refresh-token flow implemented, so a short expiration
  // (the previous default was 300 seconds) would make the app unusable — the user would be bounced
  // to /login mid-task with no way to silently renew. Revisit once refresh rotation exists.
  private val expirationSeconds = 12 * 60 * 60

  def issueToken(user: UserClaim, secretKey: String): String = {
    val content    = user.toJson
    val issuedAt   = Instant.now.getEpochSecond
    val expiration = Instant.now.plusSeconds(expirationSeconds).getEpochSecond
    val claim      = JwtClaim(content, issuedAt = Some(issuedAt), expiration = Some(expiration))
    Jwt.encode(claim, secretKey, ALG)
  }

  case class UserClaim(userId: String, email: String, role: String)
