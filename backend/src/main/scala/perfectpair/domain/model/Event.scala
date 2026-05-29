package perfectpair.domain.model

import perfectpair.common.model.model.ID
import upickle.ReadWriter

import java.util.UUID

sealed trait Event derives ReadWriter {
  val id: UUID
  val eventType: String = this.getClass.getSimpleName
}

case class MoneyMovementsIngested(id: ID = UUID.randomUUID(), movementSpecID: ID) extends Event

case class AccountReconciled(id: ID = UUID.randomUUID(), accountID: ID) extends Event

case class NewFileDetected(id: ID = UUID.randomUUID(), fileInventoryID: ID) extends Event
