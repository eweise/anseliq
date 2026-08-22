package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.DataSourceConfig
import onparity.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class MovementSpec(
  @Id id:         ID                     = createID(),
  name:           String                 = "",
  inAccountId:    ID,
  outAccountId:   ID,
  dataSourceJson: String                 = "",
  var createdAt:  Option[OffsetDateTime] = None,
  var updatedAt:  Option[OffsetDateTime] = None,
  // How often a new file/feed is expected for this spec, e.g. 24 for "expect one every 24 hours".
  // None means no cadence expectation is configured (not every spec is fed by a scheduled file
  // drop), so file-missing detection has nothing to compare against and simply doesn't apply.
  // Field order matters here: Magnum reads columns positionally, and this column was added via
  // ALTER TABLE (which always appends at the physical end) after created_at/updated_at already
  // existed, so it must stay declared last to match the live column order.
  expectedCadenceHours: Option[Int] = None
) derives DbCodec:
  def dataSourceConfig(): DataSourceConfig = upickle.read(dataSourceJson)

object MovementSpec:
  val Table = TableInfo[MovementSpec, MovementSpec, UUID]
