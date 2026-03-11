package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID
import perfectpair.domain.model.ReconciliationStatus.Final

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum ReconciliationStatus derives DbCodec:
  case InProgress, Final

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
  @Id id:       ID                   = createID(),
  specId:       ID,
  appliedRules: String               = "",
  description:  String               = "",
  status:       ReconciliationStatus = Final
) extends Auditable
    derives DbCodec

object Reconciliation:
  val Table = TableInfo[Reconciliation, Reconciliation, UUID]
