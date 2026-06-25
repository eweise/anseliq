package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.{ID, Money}
import perfectpair.domain.model.helper.JSONBProps

import java.time.OffsetDateTime
import java.util
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Account(
  @Id id:                  ID                     = createID(),
  name:                    String,
  fundFlowId:              ID,
  properties:              JSONBProps             = JSONBProps(Map()),
  var balance:             Money                  = 0,
  var reconciledBalance:   Money                  = 0,
  var unreconciledBalance: Money                  = 0,
  isRecon:                 Boolean                = false,
  var createdAt:           Option[OffsetDateTime] = None,
  var updatedAt:           Option[OffsetDateTime] = None
) derives DbCodec

object Account:
  val Table = TableInfo[Account, Account, UUID]
