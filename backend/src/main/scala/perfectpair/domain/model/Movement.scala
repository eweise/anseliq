package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.{ID, Money}
import perfectpair.domain.model.helper.JSONBProps

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Movement(
  @Id id:               ID                     = createID(),
  referenceId:          ID                     = createID(), // serves as the idempotency id //todo remove default
  outAccountId:         ID,
  outAccountBalance:    Money                  = 0,
  inAccountId:          ID,
  inAccountBalance:     Money                  = 0,
  amount:               Money,
  properties:           JSONBProps             = JSONBProps(Map()),
  movementSpecId:       ID,
  var reconciliationId: Option[ID]             = None,
  var createdAt:        Option[OffsetDateTime] = None,
  var updatedAt:        Option[OffsetDateTime] = None
) derives DbCodec:

  def props = this.properties.props

  def signedAmountFor(accountID: ID): Money =
    if inAccountId == accountID then amount else -amount

object Movement:
  val Table = TableInfo[Movement, Movement, UUID]
