package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.{ID, Money}
import perfectpair.domain.model.ReconciliationStatus.Final

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum ReconciliationStatus derives DbCodec:
  case InProgress, Final

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
  @Id id:        ID                     = createID(),
  specId:        ID,
  appliedRules:  String                 = "",
  description:   String                 = "",
  var status:    ReconciliationStatus   = Final,
  var balance:   Money                  = 0,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec

object Reconciliation:
  val Table = TableInfo[Reconciliation, Reconciliation, UUID]
