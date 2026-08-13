package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeTarget derives DbCodec:
  case Movement, Reconciliation

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeCategory derives DbCodec:
  case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeStatus derives DbCodec:
  case New, Investigating, Escalated, Resolved

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Dispute(
  @Id id:            ID                     = createID(),
  referenceId:       ID,
  targetType:        DisputeTarget,
  category:          DisputeCategory,
  detail:            Option[String]         = None,
  var status:         DisputeStatus          = DisputeStatus.New,
  var resolutionNote: Option[String]         = None,
  var createdAt:      Option[OffsetDateTime] = None,
  var updatedAt:      Option[OffsetDateTime] = None
) derives DbCodec

object Dispute:
  val Table = TableInfo[Dispute, Dispute, UUID]
