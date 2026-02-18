package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
  @Id id:      ID     = createID(),
  specId:      ID,
  description: String = ""
) extends Auditable
    derives DbCodec

object Reconciliation:
  val Table = TableInfo[Reconciliation, Reconciliation, UUID]
