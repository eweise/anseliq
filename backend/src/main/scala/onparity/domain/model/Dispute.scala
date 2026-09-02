package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeTarget derives DbCodec:
  case Movement, Reconciliation, MovementSpec, FileFeed

enum DisputeCategory derives DbCodec:
  case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality, FileMissing,
    UnroutedRecord, ExcessiveParseErrors

/**
 * Which DisputeTarget(s) a category is meaningful against — e.g. FileMissing has no FileInventory
 * row to reference (that's the whole point of the category) so it can only ever target the FileFeed
 * that expected the file, while AmountMismatch is only ever raised against a Reconciliation.
 * Checked by DisputeService.raise so a mismatched pairing (wrong Scala call site, a bad manual API
 * request, or a raw DB write) is rejected at the one place it actually needs to be caught — it
 * can't be enforced at compile time because Dispute crosses two serialization boundaries (the JSON
 * payload and the Postgres row) where any type-level link is already erased.
 */
extension (category: DisputeCategory)
  def validTargets: Set[DisputeTarget] = category match
    case DisputeCategory.AmountMismatch     => Set(DisputeTarget.Reconciliation)
    case DisputeCategory.MissingCounterpart => Set(DisputeTarget.Movement)
    case DisputeCategory.Duplicate          => Set(DisputeTarget.Movement)
    case DisputeCategory.TimingDifference   => Set(DisputeTarget.Movement)
    case DisputeCategory.DataQuality        => Set(DisputeTarget.Movement)
    case DisputeCategory.FileMissing        => Set(DisputeTarget.FileFeed)
    case DisputeCategory.UnroutedRecord     => Set(DisputeTarget.FileFeed)
    case DisputeCategory.ExcessiveParseErrors => Set(DisputeTarget.FileFeed)

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeStatus derives DbCodec:
  case New, Investigating, Escalated, Resolved

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Dispute(
  @Id id:      ID             = createID(),
  referenceId: ID,
  targetType:  DisputeTarget,
  category:    DisputeCategory,
  detail:      Option[String] = None,
  // None means system-raised (the two InMemoryReconciler.scala sites and Ingestor's
  // raiseFileMissingDispute all insert directly via disputeRepo, bypassing DisputeService.raise,
  // so they never have a human actor to attribute). assignedToUserId starts unset for every
  // dispute — nothing assigns one automatically; DisputeService.assign is the only way it's set.
  raisedByUserId:       Option[ID]             = None,
  var assignedToUserId: Option[ID]             = None,
  var status:           DisputeStatus          = DisputeStatus.New,
  var createdAt:        Option[OffsetDateTime] = None,
  var updatedAt:        Option[OffsetDateTime] = None
) derives DbCodec

object Dispute:
  val Table = TableInfo[Dispute, Dispute, UUID]

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class DisputeComment(
  @Id id:                  ID                     = createID(),
  disputeId:               ID,
  body:                    Option[String]         = None,
  newStatus:               Option[DisputeStatus]  = None,
  newAssignedToUserId:     Option[ID]             = None,
  var createdAt:           Option[OffsetDateTime] = None
) derives DbCodec

object DisputeComment:
  val Table = TableInfo[DisputeComment, DisputeComment, UUID]
