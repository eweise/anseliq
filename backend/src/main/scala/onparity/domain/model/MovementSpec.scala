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
  var updatedAt:  Option[OffsetDateTime] = None
) derives DbCodec:
  def dataSourceConfig(): DataSourceConfig = upickle.read(dataSourceJson)

object MovementSpec:
  val Table = TableInfo[MovementSpec, MovementSpec, UUID]
