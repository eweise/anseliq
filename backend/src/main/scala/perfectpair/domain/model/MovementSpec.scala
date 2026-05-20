package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.DataSourceConfig
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class MovementSpec(
  @Id id:         ID                     = createID(),
  inAccountId:    ID,
  outAccountId:   ID,
  dataSourceJson: String                 = "",
  var createdAt:  Option[OffsetDateTime] = None,
  var updatedAt:  Option[OffsetDateTime] = None
) derives DbCodec:
  def dataSourceConfig(): DataSourceConfig = upickle.read(dataSourceJson)

object MovementSpec:
  val Table = TableInfo[MovementSpec, MovementSpec, UUID]
