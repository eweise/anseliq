package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.{ID, Money, Properties}
import perfectpair.domain.model.helper.JSONBProps

import java.util
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Account(
  @Id id:      ID         = createID(),
  name:        String,
  fundFlowId:  ID,
  properties:  JSONBProps = JSONBProps(Map()),
  var balance: Money      = 0
) extends Auditable
    derives DbCodec

object Account:
  val Table = TableInfo[Account, Account, UUID]
