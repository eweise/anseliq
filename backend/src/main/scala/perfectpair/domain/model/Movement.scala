package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.{ID, Money}
import MovementStatus.{Reconciled, WithinSLA}
import perfectpair.domain.model.helper.JSONBProps

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum MovementStatus derives DbCodec:
  case Reconciled, WithinSLA, OutOfSLA

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Movement(
  @Id id:               ID             = createID(),
  referenceId:          ID             = createID(), // serves as the idempotency id //todo remove default
  outAccountId:         ID,
  inAccountId:          ID,
  amount:               Money,
  properties:           JSONBProps     = JSONBProps(Map()),
  movementSpecId:       ID,
  var reconciliationId: Option[ID]     = None,
  var status:           MovementStatus = WithinSLA
) extends Auditable
    derives DbCodec

object Movement:
  val Table = TableInfo[Movement, Movement, UUID]

extension (vc: Vector[Movement]) def isReconciled = vc.foldLeft(true)((isReconciled, m) => if !isReconciled then false else m.status == Reconciled)
