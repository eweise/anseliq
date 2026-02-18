package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.DataSourceConfig
import perfectpair.common.model.model.ID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class MovementSpec(
  @Id id:         ID = createID(),
  name:           String,
  inAccountId:    ID,
  outAccountId:   ID,
  dataSourceJson: String = "",
) extends Auditable
    derives DbCodec:
  def dataSourceConfig(): DataSourceConfig = upickle.read(dataSourceJson)
