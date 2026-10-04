package anseliq.domain.model

import com.augustnagro.magnum.*
import anseliq.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FundFlowConfig(
  @Id id:        ID                     = createID(),
  name:          String,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None,
  // Hour of day (0-23) after which the daily reporting snapshot for "today" may be generated.
  // Must stay last: ALTER TABLE appends new columns physically, and Magnum maps DbCodec fields
  // positionally, so this has to match the live table's actual column order.
  dailyCutoffHours: Int = 0
) derives DbCodec

object FundFlowConfig:
  val Table: TableInfo[FundFlowConfig, FundFlowConfig, UUID] = TableInfo[FundFlowConfig, FundFlowConfig, UUID]
