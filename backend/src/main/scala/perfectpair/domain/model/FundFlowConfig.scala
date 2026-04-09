package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FundFlowConfig(
  @Id id:        ID                     = createID(),
  name:          String,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec

object FundFlowConfig:
  val Table: TableInfo[FundFlowConfig, FundFlowConfig, UUID] = TableInfo[FundFlowConfig, FundFlowConfig, UUID]
