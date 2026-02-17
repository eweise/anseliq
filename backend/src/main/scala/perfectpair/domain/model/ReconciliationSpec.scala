package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.Matcher
import perfectpair.common.model.model.ID

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class ReconciliationSpec(
  @Id id:            ID     = createID(),
  @Id fundFlowId:    ID,
  description:       String = "",
  agingLimit:        Int    = 0,
  matchingRulesJson: String = "{}"
) extends Auditable
    derives DbCodec:
  def matcher():Matcher = upickle.read(matchingRulesJson)

object ReconciliationSpec:
  val Table = TableInfo[ReconciliationSpec, ReconciliationSpec, UUID]
