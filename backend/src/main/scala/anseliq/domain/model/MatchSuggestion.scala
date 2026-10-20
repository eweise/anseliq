package anseliq.domain.model

import anseliq.common.model.model.ID
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum MatchSuggestionStatus derives DbCodec:
  case Pending, Accepted, Rejected

// A secondary matcher's proposal to reconcile movementId with suggestedMovementIds, for a movement
// the recon spec's rule left unmatched. Only ever a suggestion: a person accepts or rejects it.
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class MatchSuggestion(
  @Id id:                   ID                     = createID(),
  reconSpecId:              ID,
  movementId:               ID,
  suggestedMovementIdsJson: String,
  confidence:               BigDecimal,
  rationale:                String,
  model:                    String,
  promptVersion:            String,
  var status:               MatchSuggestionStatus  = MatchSuggestionStatus.Pending,
  // Who accepted or rejected it; None while pending.
  var reviewedByUserId:     Option[ID]             = None,
  var createdAt:            Option[OffsetDateTime] = None,
  var updatedAt:            Option[OffsetDateTime] = None
) extends Audited derives DbCodec:
  def suggestedMovementIds: Seq[ID] = upickle.read[Seq[UUID]](suggestedMovementIdsJson)

object MatchSuggestion:
  val Table = TableInfo[MatchSuggestion, MatchSuggestion, UUID]
