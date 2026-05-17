package perfectpair.domain.model

import perfectpair.common.model.model.ID
import upickle.ReadWriter

import java.util.UUID

sealed trait Event derives ReadWriter {
  val id: UUID
  val eventType: String = this.getClass.getSimpleName
}

case class MoneyMovementsIngested(id: UUID = UUID.randomUUID(), movementSpecID: ID) extends Event

case class AccountReconciled(id: UUID = UUID.randomUUID(), accountID: ID) extends Event
