package com.eweise.perfectpair.domain.model

import com.augustnagro.magnum.*
import com.eweise.perfectpair.domain.model.MovementStatus.{Reconciled, WithinSLA}
import com.eweise.perfectpair.domain.model.helper.JSONBProps

import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum MovementStatus derives DbCodec:
  case Reconciled, WithinSLA, OutOfSLA

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Movement(
  @Id id: ID = createID(),
  referenceId: ID = createID(), // serves as the idempotency id //todo remove default
  outAccountId: ID,
  inAccountId: ID,
  amount: Money,
  properties: JSONBProps = JSONBProps(Map()),
  reconciliationId: Option[ID] = None,
  status: MovementStatus = WithinSLA
) extends Auditable
    derives DbCodec

object Movement:
  val Table = TableInfo[Movement, Movement, UUID]

extension (vc: Vector[Movement]) def isReconciled = vc.foldLeft(true)((isReconciled, m) => if !isReconciled then false else m.status == Reconciled)
