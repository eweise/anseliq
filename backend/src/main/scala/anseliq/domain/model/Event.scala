package anseliq.domain.model

import anseliq.common.model.model.ID
import upickle.ReadWriter

import java.util.UUID

sealed trait Event derives ReadWriter {
  val id: UUID
  val eventType: String = this.getClass.getSimpleName
}

case class MoneyMovementsIngested(
  id:                   ID = UUID.randomUUID(),
  movementSpecId:       ID,
  movementSpecName:     String,
  fileName:             String,
  recordCount:          Int,
  ingestionTimeSeconds: Long,
  lastProcessedLine:    Option[Long]
) extends Event

case class AccountReconciled(id: ID = UUID.randomUUID(), accountId: ID) extends Event

case class NewFileDetected(
  id:              ID = UUID.randomUUID(),
  fileInventoryId: ID,
  filePath:        String,
  source:          String
) extends Event

case class ReconciliationManuallyMatched(id: ID = UUID.randomUUID(), reconciliationId: ID) extends Event

case class ReconciliationVoided(id: ID = UUID.randomUUID(), reconciliationId: ID) extends Event

case class ReconciliationWrittenOff(id: ID = UUID.randomUUID(), reconciliationId: ID, reason: String) extends Event

case class ReconciliationWriteOffReversed(
  id:               ID         = UUID.randomUUID(),
  reconciliationId: ID,
  reason:           String,
  reversedByUserId: Option[ID] = None
) extends Event

case class ExceptionRaised(id: ID = UUID.randomUUID(), exceptionId: ID) extends Event

case class ExceptionStatusChanged(id: ID = UUID.randomUUID(), exceptionId: ID, newStatus: String) extends Event

case class ExceptionCommentAdded(id: ID = UUID.randomUUID(), exceptionId: ID, commentId: ID) extends Event
