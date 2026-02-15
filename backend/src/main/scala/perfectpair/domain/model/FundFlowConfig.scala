package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID
import upickle.default.*

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FundFlowConfig(
  @Id id: ID = createID(),
  name:   String
) extends Auditable
    derives DbCodec,
      ReadWriter

object FundFlowConfig:
  val Table = TableInfo[FundFlowConfig, FundFlowConfig, UUID]
