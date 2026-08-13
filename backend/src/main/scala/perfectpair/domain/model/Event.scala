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

case class ReconciliationManuallyMatched(id: ID = UUID.randomUUID(), reconciliationID: ID) extends Event

case class ReconciliationVoided(id: ID = UUID.randomUUID(), reconciliationID: ID) extends Event

case class ReconciliationWrittenOff(id: ID = UUID.randomUUID(), reconciliationID: ID, reason: String) extends Event

case class DisputeRaised(id: ID = UUID.randomUUID(), disputeID: ID) extends Event

case class DisputeStatusChanged(id: ID = UUID.randomUUID(), disputeID: ID, newStatus: String) extends Event

case class DisputeCommentAdded(id: ID = UUID.randomUUID(), disputeID: ID, commentID: ID) extends Event
