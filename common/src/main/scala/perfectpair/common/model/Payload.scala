package perfectpair.common.model

import perfectpair.common.model.model.{Money, Properties}
import upickle.default.*
import zio.json.*

import java.time.format.DateTimeFormatter
import java.time.{Instant, OffsetDateTime}

type ValidationErrors = Map[String, String]

trait CanValidate:
  def validate(): ValidationErrors

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

import perfectpair.common.model.OffsetDateTimeRW.given

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
  id:        Option[String]         = None,
  name:      String                 = "",
  accounts:  Seq[AccountPayload]    = Seq(),
  createdAt: Option[OffsetDateTime] = None,
  updatedAt: Option[OffsetDateTime] = None
) derives ReadWriter

case class ReconciliationSpecPayload(
  id:                Option[String] = None,
  accountId:         Option[String] = None,
  description:       String         = "",
  agingLimit:        Int            = 0,
  relationship:      String         = "OneToOne",
  inMovementSpecId:  String         = "",
  outMovementSpecId: String         = "",
  rule:              Rule           = NoOpRule
) derives ReadWriter

case class AccountPayload(
  id:         Option[String] = None,
  name:       String         = "",
  fundFlowId: String         = "",
  properties: Properties     = Map(),
  balance:    Money          = 0
) extends CanValidate
    derives ReadWriter:
  override def validate(): ValidationErrors =
    var errors = Map[String, String]()
    if name.length < 3 then errors += ("name" -> "name must be at least 3 characters long")
    errors

case class MovementSpecPayload(
  id:               Option[String]           = None,
  name:             String                   = "",
  inAccountId:      String                   = "",
  outAccountId:     String                   = "",
  dataSourceConfig: Option[DataSourceConfig] = None
) derives ReadWriter

case class MovementPayload(
  id:               String,
  referenceId:      String,
  outAccountId:     String,
  inAccountId:      String,
  amount:           Money,
  properties:       Properties,
  reconciliationId: String
) derives ReadWriter

case class MovementSpecAndAccounts(
  spec:       MovementSpecPayload,
  inAccount:  AccountPayload,
  outAccount: AccountPayload
) derives ReadWriter

def combineAccountsAndMovementSpecs(
  accounts:      Seq[AccountPayload],
  movementSpecs: Seq[MovementSpecPayload]
): Seq[MovementSpecAndAccounts] =
  val accountMap = accounts.map(a => a.id.get -> a).toMap
  movementSpecs.map(ms =>
    MovementSpecAndAccounts(
      spec = ms,
      inAccount = accountMap
        .getOrElse(ms.inAccountId, throw IllegalStateException(s"could not find inAccount ${ms.inAccountId}")),
      outAccount = accountMap.getOrElse(
        ms.outAccountId,
        throw IllegalStateException(s"could not find outAccount ${ms.outAccountId}")
      )
    )
  )

def combineReconAndMovementSpecs(
  accounts:      Seq[AccountPayload],
  movementSpecs: Seq[MovementSpecPayload]
): Seq[MovementSpecAndAccounts] =
  val accountMap = accounts.map(a => a.id.get -> a).toMap
  movementSpecs.map(ms =>
    MovementSpecAndAccounts(
      spec = ms,
      inAccount = accountMap
        .getOrElse(ms.inAccountId, throw IllegalStateException(s"could not find inAccount ${ms.inAccountId}")),
      outAccount = accountMap.getOrElse(
        ms.outAccountId,
        throw IllegalStateException(s"could not find outAccount ${ms.outAccountId}")
      )
    )
  )
