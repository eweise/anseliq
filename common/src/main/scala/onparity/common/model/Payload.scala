package onparity.common.model

import onparity.common.model.ReconRelationship.OneToOne
import onparity.common.model.model.{Money, Properties}
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

import onparity.common.model.OffsetDateTimeRW.given

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

enum JobTypeInfo derives ReadWriter:
  case Ingestion, Reconciliation, CreateFileInventory

enum JobInfoStatus derives ReadWriter:
  case Submitted, InProgress, Complete, Error

case class JobInfoPayload(
  id:           Option[String]         = None,
  referenceId:  String                 = "",
  jobType:      JobTypeInfo,
  jobStatus:    JobInfoStatus,
  jobInfoJson:  Option[String],
  jobInfo:      Option[JobInfo]        = None,
  errorMessage: Option[String]         = None,
  createdAt:    Option[OffsetDateTime] = None,
  updatedAt:    Option[OffsetDateTime] = None
) derives ReadWriter

case class FundFlowPayload(
  id:               Option[String]         = None,
  name:             String                 = "",
  accounts:         Seq[AccountPayload]    = Seq(),
  createdAt:        Option[OffsetDateTime] = None,
  updatedAt:        Option[OffsetDateTime] = None,
  dailyCutoffHours: Int                    = 0
) derives ReadWriter

enum ReconRelationship derives ReadWriter:
  case OneToOne, OneToMany, ManyToMany

case class ReconciliationSpecPayload(
  id:                Option[String]    = None,
  accountId:         Option[String]    = None,
  agingLimit:        Int               = 0,
  relationship:      ReconRelationship = OneToOne,
  inMovementSpecId:  String            = "",
  outMovementSpecId: String            = "",
  rule:              Rule              = NoOpRule
) derives ReadWriter

case class ReconciliationPayload(
  id:               String,
  reconSpecId:      String,
  appliedRules:     String,
  description:      String,
  status:           String,
  balance:          Money,
  adjustmentReason: Option[String],
  var createdAt:    OffsetDateTime,
  var updatedAt:    OffsetDateTime
) derives ReadWriter

case class ReconciliationMovementPayload(
  id:     String,
  amount: Money
) derives ReadWriter

// Distinct from ReconciliationSpecPayload (the maintain-form shape, keyed by accountId and the
// parsed Rule): this is a read-only summary for the reconciliation detail page, exposing the raw
// ruleJson and a resolved accountName rather than requiring the caller to look either up itself.
case class ReconciliationSpecSummaryPayload(
  id:           String,
  agingLimit:   Int,
  relationship: ReconRelationship,
  ruleJson:     String,
  accountName:  String
) derives ReadWriter

case class ReconciliationDetailPayload(
  reconciliation: ReconciliationPayload,
  spec:           ReconciliationSpecSummaryPayload,
  movements:      Seq[ReconciliationMovementPayload]
) derives ReadWriter

case class ManualMatchRequest(
  inMovementIds:  Seq[String],
  outMovementIds: Seq[String],
  description:    String = "Manual match"
) derives ReadWriter

case class WriteOffRequest(reason: String) derives ReadWriter

case class FundFlowSummaryViewPayload(
  fundFlow: FundFlowPayload,
  accounts: Seq[AccountSummaryViewPayload]
) derives ReadWriter

case class AccountSummaryViewPayload(
  account:           AccountPayload,
  isReconAccount:    Boolean,
  unreconciledCount: Int
) derives ReadWriter

case class AccountPayload(
  id:                  Option[String] = None,
  name:                String         = "",
  fundFlowId:          String         = "",
  properties:          Properties     = Map(),
  isRecon:             Boolean        = false,
  balance:             Money          = 0,
  reconciledBalance:   Money          = 0,
  unreconciledBalance: Money          = 0
) extends CanValidate
    derives ReadWriter:
  override def validate(): ValidationErrors =
    var errors = Map[String, String]()
    if name.length < 3 then errors += ("name" -> "name must be at least 3 characters long")
    errors

case class MovementSpecPayload(
  id:                   Option[String]           = None,
  name:                 String                   = "",
  inAccountId:          String                   = "",
  outAccountId:         String                   = "",
  dataSourceConfig:     Option[DataSourceConfig] = None,
  expectedCadenceHours: Option[Int]              = None
) derives ReadWriter

case class MovementPayload(
  id:               String,
  referenceId:      String,
  outAccountId:     String,
  inAccountId:      String,
  amount:           Money,
  properties:       Properties,
  reconciliationId: String,
  createdAt:        OffsetDateTime
) derives ReadWriter

case class MovementSpecAndAccounts(
  spec:       MovementSpecPayload,
  inAccount:  AccountPayload,
  outAccount: AccountPayload
) derives ReadWriter

enum DisputeTargetPayload derives ReadWriter:
  case Movement, Reconciliation, MovementSpec

enum DisputeCategoryPayload derives ReadWriter:
  case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality, FileMissing

enum DisputeStatusPayload derives ReadWriter:
  case New, Investigating, Escalated, Resolved

case class RaiseDisputeRequest(
  referenceId: String,
  targetType:  DisputeTargetPayload,
  category:    DisputeCategoryPayload,
  detail:      Option[String] = None
) derives ReadWriter

case class DisputePayload(
  id:             String,
  referenceId:    String,
  targetType:     DisputeTargetPayload,
  category:       DisputeCategoryPayload,
  detail:         Option[String],
  status:         DisputeStatusPayload,
  accountId:      String,
  accountName:    String,
  referenceLabel: String,
  createdAt:      OffsetDateTime,
  updatedAt:      OffsetDateTime
) derives ReadWriter

case class DisputeCommentPayload(
  id:        String,
  body:      Option[String],
  newStatus: Option[DisputeStatusPayload],
  createdAt: OffsetDateTime
) derives ReadWriter

case class DisputeDetailPayload(
  dispute:        DisputePayload,
  movement:       Option[MovementPayload],
  reconciliation: Option[ReconciliationPayload],
  comments:       Seq[DisputeCommentPayload]
) derives ReadWriter

case class AddDisputeCommentRequest(
  body:      Option[String]               = None,
  newStatus: Option[DisputeStatusPayload] = None
) derives ReadWriter

case class MatchRatePayload(
  accountId:         String,
  accountName:       String,
  balance:           Money,
  reconciledBalance: Money,
  matchRatePercent:  Option[BigDecimal]
) derives ReadWriter

case class ExceptionBreakdownPayload(
  byCategory: Map[String, Int],
  byStatus:   Map[String, Int]
) derives ReadWriter

case class AgingBucketsPayload(
  buckets:      Seq[(String, Int)],
  overdueCount: Int
) derives ReadWriter

case class ReportsSummaryPayload(
  matchRates: Seq[MatchRatePayload],
  exceptions: ExceptionBreakdownPayload,
  aging:      AgingBucketsPayload
) derives ReadWriter

case class TrendPointPayload(
  date:                     String, // ISO-8601 (yyyy-MM-dd)
  avgMatchRatePercent:      Option[BigDecimal],
  openExceptionCount:       Int,
  openExceptionsByCategory: Map[String, Int],
  unreconciledBacklogCount: Int,
  overdueCount:             Int
) derives ReadWriter

case class TrendReportPayload(
  points: Seq[TrendPointPayload]
) derives ReadWriter

case class FileInventoryPayload(
  id:                String,
  movementSpecId:    String,
  movementSpecName:  String,
  sourceSystem:      String,
  fileName:          String,
  fileSizeBytes:     Long,
  lastModifiedAt:    OffsetDateTime,
  status:            String,
  lastProcessedAt:   Option[OffsetDateTime],
  lastProcessedLine: Option[Long],
  updatedAt:         Option[OffsetDateTime]
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

sealed trait EventPayload derives ReadWriter:
  val id:           String
  val eventType:    String
  val status:       String
  val retryCount:   Int
  val errorMessage: Option[String]
  val createdAt:    Option[OffsetDateTime]
  val updatedAt:    Option[OffsetDateTime]

case class MoneyMovementsIngestedPayload(
  id:                   String,
  eventType:            String,
  movementSpecID:       String,
  movementSpecName:     String,
  fileName:             String,
  recordCount:          Int,
  ingestionTimeSeconds: Long,
  lastProcessedLine:    Option[Long],
  status:               String,
  retryCount:           Int,
  errorMessage:         Option[String],
  createdAt:            Option[OffsetDateTime],
  updatedAt:            Option[OffsetDateTime]
) extends EventPayload

case class AccountReconciledPayload(
  id:           String,
  eventType:    String,
  accountID:    String,
  status:       String,
  retryCount:   Int,
  errorMessage: Option[String],
  createdAt:    Option[OffsetDateTime],
  updatedAt:    Option[OffsetDateTime]
) extends EventPayload

case class NewFileDetectedPayload(
  id:              String,
  eventType:       String,
  fileInventoryID: String,
  status:          String,
  retryCount:      Int,
  errorMessage:    Option[String],
  createdAt:       Option[OffsetDateTime],
  updatedAt:       Option[OffsetDateTime]
) extends EventPayload
