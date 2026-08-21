package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.model.{ID, Money}
import onparity.domain.model.ReconciliationStatus.Final

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum ReconciliationStatus derives DbCodec:
  case InProgress, Final, Voided

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
  @Id id:               ID                     = createID(),
  specId:               ID,
  appliedRules:         String                 = "",
  description:          String                 = "",
  var status:           ReconciliationStatus   = Final,
  var balance:          Money                  = 0,
  var adjustmentReason: Option[String]         = None,
  var createdAt:        Option[OffsetDateTime] = None,
  var updatedAt:        Option[OffsetDateTime] = None
) derives DbCodec

object Reconciliation:
  val Table = TableInfo[Reconciliation, Reconciliation, UUID]
