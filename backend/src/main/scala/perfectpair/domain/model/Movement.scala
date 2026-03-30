package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.{ID, Money}
import perfectpair.domain.model.helper.JSONBProps

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Movement(
  @Id id:               ID         = createID(),
  referenceId:          ID         = createID(), // serves as the idempotency id //todo remove default
  outAccountId:         ID,
  inAccountId:          ID,
  amount:               Money,
  properties:           JSONBProps = JSONBProps(Map()),
  movementSpecId:       ID,
  var reconciliationId: Option[ID] = None
) extends Auditable
    derives DbCodec:

  def props = this.properties.props

object Movement:
  val Table = TableInfo[Movement, Movement, UUID]
