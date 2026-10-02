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

  /**
   * For a category whose underlying problem the system itself clears, what clears it: Some(text)
   * means a person can't mark it Resolved by hand (DisputeService.addComment rejects it), because
   * resolving the dispute wouldn't fix anything. A resolved AmountMismatch would leave its
   * reconciliation Mismatched, with the difference still on the books, just no longer in anyone's
   * queue. The real fixes resolve it automatically (DisputeService.resolveAutomatically, called by
   * ManualReconciler, InMemoryReconciler and Ingestor). None: a judgment call a person closes with a
   * comment.
   */
  def automaticResolution: Option[String] = category match
    case DisputeCategory.AmountMismatch =>
      Some("It resolves when the reconciliation is written off, unmatched, or brought into balance by a late movement.")
    case DisputeCategory.FileMissing => Some("It resolves when the expected file arrives.")
    case _                           => None

  /**
   * For the same categories: why a resolved one can't be reopened by hand, and what to do instead.
   * The system closed it because the problem was fixed, and nothing would ever resolve it again,
   * so a reopened one would be stuck open. If the fix itself was wrong, undoing the fix brings
   * the problem back, and the system raises a new dispute for it.
   */
  def insteadOfReopening: Option[String] = category match
    case DisputeCategory.AmountMismatch =>
      Some(
        "If the write-off was wrong, an admin can reverse it from the reconciliation, which puts it back to Mismatched with a new dispute. If the match was wrong, unmatch the reconciliation."
      )
    case DisputeCategory.FileMissing => Some("A new dispute is raised if the feed goes overdue again.")
    case _                           => None

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
  // None means system-raised (DisputeService.raiseSystemDispute: the reconcilers' AmountMismatch
  // disputes and Ingestor's file feed disputes), which has no human actor to attribute. assignedToUserId starts unset for every
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
  // Another dispute this entry points to, shown as a link on the thread — e.g. when reversing a
  // write-off re-raises a mismatch, the old (resolved) and new disputes each link to the other.
  relatedDisputeId: Option[ID] = None,
  var createdAt:           Option[OffsetDateTime] = None
) derives DbCodec

object DisputeComment:
  val Table = TableInfo[DisputeComment, DisputeComment, UUID]
