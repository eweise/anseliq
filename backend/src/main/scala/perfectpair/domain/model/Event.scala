package perfectpair.domain.model

import perfectpair.common.model.model.ID
import upickle.ReadWriter

import java.util.UUID

sealed trait Event derives ReadWriter {
  val id:        UUID
  val eventType: String
}

case class MoneyMovementsIngested(id: UUID = UUID.randomUUID(), movementSpecID: ID) extends Event:
  override val eventType: String = "MoneyMovementIngested"

case class AccountReconciled(id: UUID = UUID.randomUUID(), accountID: ID) extends Event:
  override val eventType: String = "AccountReconciled"
