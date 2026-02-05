package com.eweise.perfectpair.common.model

import com.eweise.perfectpair.common.model.AccountPayload
import com.eweise.perfectpair.common.model.model.{ID, Money, Properties}
import upickle.default.*
import zio.json.*

import java.time.OffsetDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter

object InstantRW {
  // Use ISO_INSTANT formatter for standard representation
  val formatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

  // Define the implicit ReadWriter
  implicit val rw: ReadWriter[Instant] = readwriter[String].bimap[Instant](
    instant => formatter.format(instant), // Serialize to string
    str => Instant.parse(str)             // Deserialize from string
  )
}

object OffsetDateTimeRW {
  // Use ISO_OFFSET_DATE_TIME formatter for standard representation
  val formatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  // Define the given ReadWriter for Scala 3
  given rw: ReadWriter[OffsetDateTime] = readwriter[String].bimap[OffsetDateTime](
    odt => formatter.format(odt),    // Serialize to string
    str => OffsetDateTime.parse(str) // Deserialize from string
  )
}

import OffsetDateTimeRW.given

/**
 * Shared models between Backend and Frontend
 */
case class LoginRequest(username: String, password: String)
case class LoginSuccess(accessToken: String, refreshToken: String)
case class LoginFailure(reason: String)
case class RandomMessage(text: String) derives ReadWriter

object LoginRequest {
  given JsonCodec[LoginRequest] = DeriveJsonCodec.gen[LoginRequest]
}

object LoginSuccess {
  given JsonCodec[LoginSuccess] = DeriveJsonCodec.gen[LoginSuccess]
}

object LoginFailure {
  given JsonCodec[LoginFailure] = DeriveJsonCodec.gen[LoginFailure]
}

object RandomMessage {
  given JsonCodec[RandomMessage] = DeriveJsonCodec.gen[RandomMessage]
}

enum MovementStatus:
  case Reconciled, WithinSLA, OutOfSLA

case class FundFlowPayload(
  id:        String                 = "",
  name:      String                 = "",
  accounts:  Seq[AccountPayload]    = Seq(),
  createdAt: Option[OffsetDateTime] = None,
  updatedAt: Option[OffsetDateTime] = None
) derives ReadWriter

case class ReconciliationAccountPayload(
  name:       String,
  matcher:    Matcher = EqualityMatcher(Array("id"), Array("id")),
  agingLimit: String  = ""
)

sealed trait Matcher derives ReadWriter
case class EqualityMatcher(
  inKeys:  Array[String],
  outKeys: Array[String]
) extends Matcher
    derives ReadWriter

case class SQLMatcher(
  sql: String
) extends Matcher
    derives ReadWriter

case class AccountPayload(
  id:         String,
  name:       String,
  fundFlowId: ID,
  properties: Properties = Map(),
  balance:    Money
) derives ReadWriter

case class MovementPayload(
  id:               String,
  referenceId:      String,
  outAccountId:     String,
  inAccountId:      String,
  amount:           Money,
  properties:       Properties,
  reconciliationId: String,
  status:           String
) derives ReadWriter

enum Direction:
  case IN, OUT
