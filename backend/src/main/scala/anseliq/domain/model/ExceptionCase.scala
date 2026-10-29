package anseliq.domain.model

import anseliq.common.model.model.ID
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum ExceptionTarget derives DbCodec:
  case Movement, Reconciliation, MovementSpec, FileFeed

enum ExceptionCategory derives DbCodec:
  case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality, FileMissing,
    UnroutedRecord, ExcessiveParseErrors

/**
 * Which ExceptionTarget(s) a category is meaningful against — e.g. FileMissing has no FileInventory
 * row to reference (that's the whole point of the category) so it can only ever target the FileFeed
 * that expected the file, while AmountMismatch is only ever raised against a Reconciliation.
 * Checked by ExceptionService.raise so a mismatched pairing (wrong Scala call site, a bad manual
 * API request, or a raw DB write) is rejected at the one place it actually needs to be caught — it
 * can't be enforced at compile time because ExceptionCase crosses two serialization boundaries (the
 * JSON payload and the Postgres row) where any type-level link is already erased.
 */
extension (category: ExceptionCategory)
  def validTargets: Set[ExceptionTarget] = category match
    case ExceptionCategory.AmountMismatch       => Set(ExceptionTarget.Reconciliation)
    case ExceptionCategory.MissingCounterpart   => Set(ExceptionTarget.Movement)
    case ExceptionCategory.Duplicate            => Set(ExceptionTarget.Movement)
    case ExceptionCategory.TimingDifference     => Set(ExceptionTarget.Movement)
    case ExceptionCategory.DataQuality          => Set(ExceptionTarget.Movement)
    case ExceptionCategory.FileMissing          => Set(ExceptionTarget.FileFeed)
    case ExceptionCategory.UnroutedRecord       => Set(ExceptionTarget.FileFeed)
    case ExceptionCategory.ExcessiveParseErrors => Set(ExceptionTarget.FileFeed)

  /**
   * For a category whose underlying problem the system itself clears, what clears it: Some(text)
   * means a person can't mark it Resolved by hand (ExceptionService.addComment rejects it), because
   * resolving the exception wouldn't fix anything. A resolved AmountMismatch would leave its
   * reconciliation Mismatched, with the difference still on the books, just no longer in anyone's
   * queue. The real fixes resolve it automatically (ExceptionService.resolveAutomatically, called
   * by ManualReconciler, InMemoryReconciler and Ingestor). None: a judgment call a person closes
   * with a comment.
   */
  def automaticResolution: Option[String] = category match
    case ExceptionCategory.AmountMismatch =>
      Some("It resolves when the reconciliation is written off, unmatched, or brought into balance by a late movement.")
    case ExceptionCategory.FileMissing => Some("It resolves when the expected file arrives.")
    case _                             => None

  /**
   * For the same categories: why a resolved one can't be reopened by hand, and what to do instead.
   * The system closed it because the problem was fixed, and nothing would ever resolve it again, so
   * a reopened one would be stuck open. If the fix itself was wrong, undoing the fix brings the
   * problem back, and the system raises a new exception for it.
   */
  def insteadOfReopening: Option[String] = category match
    case ExceptionCategory.AmountMismatch =>
      Some(
        "If the write-off was wrong, an admin can reverse it from the reconciliation, which puts it back to Mismatched with a new exception. If the match was wrong, unmatch the reconciliation."
      )
    case ExceptionCategory.FileMissing => Some("A new exception is raised if the feed goes overdue again.")
    case _                             => None

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum ExceptionStatus derives DbCodec:
  case New, Investigating, Escalated, Resolved

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ExceptionCase(
  @Id id:      ID             = createID(),
  referenceId: ID,
  targetType:  ExceptionTarget,
  category:    ExceptionCategory,
  detail:      Option[String] = None,
  // None means system-raised (ExceptionService.raiseSystemException: the reconcilers' AmountMismatch
  // exceptions and Ingestor's file feed exceptions), which has no human actor to attribute. assignedToUserId starts unset for every
  // exception — nothing assigns one automatically; ExceptionService.assign is the only way it's set.
  raisedByUserId:       Option[ID]             = None,
  var assignedToUserId: Option[ID]             = None,
  var status:           ExceptionStatus        = ExceptionStatus.New,
  var createdAt:        Option[OffsetDateTime] = None,
  var updatedAt:        Option[OffsetDateTime] = None
) derives DbCodec

object ExceptionCase:
  val Table = TableInfo[ExceptionCase, ExceptionCase, UUID]

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ExceptionComment(
  @Id id:              ID                      = createID(),
  exceptionId:         ID,
  body:                Option[String]          = None,
  newStatus:           Option[ExceptionStatus] = None,
  newAssignedToUserId: Option[ID]              = None,
  // Another exception this entry points to, shown as a link on the thread — e.g. when reversing a
  // write-off re-raises a mismatch, the old (resolved) and new exceptions each link to the other.
  relatedExceptionId: Option[ID]             = None,
  var createdAt:      Option[OffsetDateTime] = None
) derives DbCodec

object ExceptionComment:
  val Table = TableInfo[ExceptionComment, ExceptionComment, UUID]
