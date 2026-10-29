package anseliq.domain.model

import anseliq.common.model.model.ID
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Ledger(
  @Id id:        ID                     = createID(),
  name:          String,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None,
  // Hour of day (0-23) after which the daily reporting snapshot for "today" may be generated.
  // Must stay last: ALTER TABLE appends new columns physically, and Magnum maps DbCodec fields
  // positionally, so this has to match the live table's actual column order.
  dailyCutoffHours: Int = 0
) extends Audited derives DbCodec

object Ledger:
  val Table: TableInfo[Ledger, Ledger, UUID] = TableInfo[Ledger, Ledger, UUID]
