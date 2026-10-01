package onparity.common.model

import onparity.common.model.ReconRelationship.OneToOne
import onparity.common.model.model.{Money, Properties}
import upickle.default.*

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
case class LoginRequest(email: String, password: String) derives ReadWriter
case class LoginSuccess(accessToken: String, refreshToken: String) derives ReadWriter

case class UserPayload(
  id:        Option[String] = None,
  email:     String         = "",
  firstName: String         = "",
  lastName:  String         = "",
  role:      String         = "Viewer",
  active:    Boolean        = true
) derives ReadWriter

case class CreateUserRequest(
  email:     String,
  firstName: String,
  lastName:  String,
  role:      String,
  password:  String
) derives ReadWriter

case class UpdateUserRequest(
  firstName: String,
  lastName:  String,
  role:      String,
  active:    Boolean
) derives ReadWriter

case class ResetPasswordRequest(newPassword: String) derives ReadWriter

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
  id: String,
  // None only for a system-generated Reversed reconciliation (see backend's ReconciliationStatus).
  reconSpecId:      Option[String],
  appliedRules:     AppliedRules,
  description:      String,
  status:           String,
  balance:          Money,
  adjustmentReason: Option[String],
  var createdAt:    OffsetDateTime,
  var updatedAt:    OffsetDateTime
) derives ReadWriter

case class ReconciliationMovementPayload(
  id:          String,
  referenceId: String,
  amount:      Money,
  // Relative to the reconciliation spec's account: IN = money into it, OUT = money out of it. None
  // (as is otherAccountName) only for a spec-less Reversed reconciliation, which has no such account.
  direction: Option[Direction],
  // The movement's account that isn't the reconciled one — where a write-off against it would post.
  otherAccountName: Option[String],
  movementType:     Option[String]
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
  // None only for a system-generated Reversed reconciliation, which isn't governed by a real spec.
  spec:      Option[ReconciliationSpecSummaryPayload],
  movements: Seq[ReconciliationMovementPayload]
) derives ReadWriter

case class ManualMatchRequest(
  inMovementIds:  Seq[String],
  outMovementIds: Seq[String],
  description:    String = "Manual match"
) derives ReadWriter

// movementId: one of the reconciliation's movements, deciding which accounts the write-off posts
// between — see ManualReconciler.writeOff.
case class WriteOffRequest(reason: String, movementId: String) derives ReadWriter

case class BadFilePreviewRequest(fileSourceEntry: String) derives ReadWriter

case class BadFilePreviewPayload(
  movementsFound:           Int,
  alreadyReversedCount:     Int,
  reconciledCount:          Int,
  eligibleForReversalCount: Int
) derives ReadWriter

case class FixBadFileRequest(fileSourceEntry: String) derives ReadWriter

case class FixBadFileResultPayload(
  reconciliationsVoided: Int,
  movementsReversed:     Int
) derives ReadWriter

case class FundFlowSummaryViewPayload(
  fundFlow: FundFlowPayload,
  accounts: Seq[AccountSummaryViewPayload]
) derives ReadWriter

case class AccountSummaryViewPayload(
  account:           AccountPayload,
  isReconAccount:    Boolean,
  unreconciledCount: Int
) derives ReadWriter

enum AccountTypePayload derives ReadWriter:
  case Primary, Secondary, Recon

case class AccountPayload(
  id:         Option[String] = None,
  name:       String         = "",
  fundFlowId: String         = "",
  properties: Properties     = Map(),
  accountType:         AccountTypePayload = AccountTypePayload.Primary,
  balance:             Money              = 0,
  reconciledBalance:   Money              = 0,
  unreconciledBalance: Money              = 0
) extends CanValidate
    derives ReadWriter:
  override def validate(): ValidationErrors =
    var errors = Map[String, String]()
    if name.length < 3 then errors += ("name" -> "name must be at least 3 characters long")
    errors

case class FileFeedPayload(
  id:                   Option[String]           = None,
  name:                 String                   = "",
  dataSourceConfig:     Option[DataSourceConfig] = None,
  expectedCadenceHours: Option[Int]              = None
) derives ReadWriter

case class MovementSpecPayload(
  id:            Option[String]        = None,
  name:          String                = "",
  inAccountId:   String                = "",
  outAccountId:  String                = "",
  fileFeedId:    String                = "",
  routingFilter: Option[RoutingFilter] = None
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

case class ParsedRowPayload(
  lineNumber: Long,
  props:      Properties,
  amount:     Option[BigDecimal]
) derives ReadWriter

case class ParseErrorPayload(
  lineNumber: Long,
  message:    String
) derives ReadWriter

case class ParseTestResultPayload(
  rows:           Seq[ParsedRowPayload],
  errors:         Seq[ParseErrorPayload],
  linesProcessed: Int,
  truncated:      Boolean
) derives ReadWriter

// Read-only summary for the movement detail page: resolves account/spec names server-side
// (rather than requiring the caller to look them up) and carries metaData, which MovementPayload
// (used by list/table views) doesn't.
case class MovementDetailPayload(
  id:                String,
  referenceId:       String,
  outAccountName:    String,
  outAccountBalance: Money,
  inAccountName:     String,
  inAccountBalance:  Money,
  amount:            Money,
  properties:        Properties,
  metaData:          Properties,
  movementSpecName:  String,
  // None only for a row that predates this field — see backend's MovementType.
  movementType: Option[String] = None
) derives ReadWriter

case class MovementSearchRequest(
  fundFlowId:       String,
  accountId:        Option[String]         = None,
  amount:           Option[Money]          = None,
  movementSpecId:   Option[String]         = None,
  properties:       Map[String, String]    = Map.empty,
  fileSourceQuery:  Option[String]         = None,
  unreconciledOnly: Boolean                = false,
  createdFrom:      Option[OffsetDateTime] = None,
  createdTo:        Option[OffsetDateTime] = None,
  // "Ingestion" | "ManualAdjustment" | "Reversal" — see backend's MovementType.
  movementType: Option[String] = None
) derives ReadWriter

// Richer than MovementPayload (which lacks names/metaData) — mirrors MovementDetailPayload's
// shape but for a list of results rather than a single movement.
case class MovementSearchResultPayload(
  id:               String,
  referenceId:      String,
  amount:           Money,
  inAccountName:    String,
  outAccountName:   String,
  movementSpecName: String,
  properties:       Properties,
  metaData:         Properties,
  reconciled:       Boolean,
  createdAt:        OffsetDateTime,
  // None only for a row that predates this field — see backend's MovementType.
  movementType: Option[String] = None
) derives ReadWriter

case class MovementSpecAndAccounts(
  spec:       MovementSpecPayload,
  inAccount:  AccountPayload,
  outAccount: AccountPayload
) derives ReadWriter

enum DisputeTargetPayload derives ReadWriter:
  case Movement, Reconciliation, MovementSpec, FileFeed

enum DisputeCategoryPayload derives ReadWriter:
  case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality, FileMissing,
    UnroutedRecord, ExcessiveParseErrors

enum DisputeStatusPayload derives ReadWriter:
  case New, Investigating, Escalated, Resolved

case class RaiseDisputeRequest(
  referenceId: String,
  targetType:  DisputeTargetPayload,
  category:    DisputeCategoryPayload,
  detail:      Option[String] = None
) derives ReadWriter

case class DisputePayload(
  id:               String,
  referenceId:      String,
  targetType:       DisputeTargetPayload,
  category:         DisputeCategoryPayload,
  detail:           Option[String],
  status:           DisputeStatusPayload,
  accountId:        String,
  accountName:      String,
  referenceLabel:   String,
  createdAt:        OffsetDateTime,
  updatedAt:        OffsetDateTime,
  raisedByUserId:   Option[String] = None,
  raisedByName:     Option[String] = None,
  assignedToUserId: Option[String] = None,
  assignedToName:   Option[String] = None
) derives ReadWriter

case class DisputeCommentPayload(
  id:                String,
  body:              Option[String],
  newStatus:         Option[DisputeStatusPayload],
  newAssignedToName: Option[String],
  createdAt:         OffsetDateTime
) derives ReadWriter

case class DisputeDetailPayload(
  dispute:        DisputePayload,
  movement:       Option[MovementPayload],
  reconciliation: Option[ReconciliationPayload],
  comments:       Seq[DisputeCommentPayload],
  // The disputed reconciliation's movements; empty unless the target is a Reconciliation.
  reconciliationMovements: Seq[ReconciliationMovementPayload] = Seq.empty
) derives ReadWriter

case class AddDisputeCommentRequest(
  body:                Option[String]               = None,
  newStatus:           Option[DisputeStatusPayload] = None,
  newAssignedToUserId: Option[String]               = None
) derives ReadWriter

case class MatchRatePayload(
  accountId:         String,
  accountName:       String,
  balance:           Money,
  reconciledBalance: Money,
  // reconciledBalance/balance*100 — a $ coverage ratio, not a rate; can exceed 100%.
  balanceCoveragePercent:  Option[BigDecimal],
  reconciledMovementCount: Int,
  totalMovementCount:      Int,
  // The real match rate: reconciledMovementCount/totalMovementCount*100, a true 0-100% ratio.
  matchRatePercent: Option[BigDecimal]
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

// Drill-through row for the aging chart's "click to see the movements" link — one row per
// (recon account, unreconciled movement), matching AgingBucketsPayload's own per-account counting.
case class UnreconciledMovementPayload(
  movementId:  String,
  referenceId: String,
  amount:      Money,
  accountId:   String,
  accountName: String,
  ageDays:     Long,
  agingBucket: String,
  createdAt:   OffsetDateTime
) derives ReadWriter

case class TrendPointPayload(
  date:                      String, // ISO-8601 (yyyy-MM-dd)
  avgBalanceCoveragePercent: Option[BigDecimal],
  avgMatchRatePercent:       Option[BigDecimal],
  openExceptionCount:        Int,
  openExceptionsByCategory:  Map[String, Int],
  unreconciledBacklogCount:  Int,
  overdueCount:              Int
) derives ReadWriter

case class TrendReportPayload(
  points: Seq[TrendPointPayload]
) derives ReadWriter

case class FileInventoryPayload(
  id:                String,
  fileFeedId:        String,
  fileFeedName:      String,
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
  filePath:        String,
  fileSource:      String,
  status:          String,
  retryCount:      Int,
  errorMessage:    Option[String],
  createdAt:       Option[OffsetDateTime],
  updatedAt:       Option[OffsetDateTime]
) extends EventPayload
