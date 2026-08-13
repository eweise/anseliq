package perfectpair.domain.service

import perfectpair.common.model.model.ID
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*

class DisputeService(using
  disputeRepo:  DisputeRepo,
  movementRepo: MovementRepo,
  reconRepo:    ReconciliationRepo,
  eventStore:   EventStore,
  clock:        ClockService
):

  def raise(referenceId: ID, targetType: DisputeTarget, category: DisputeCategory, detail: Option[String]): Txn[Dispute] =
    targetType match
      case DisputeTarget.Movement       => movementRepo.findById(referenceId).ErrIfNotFound("movement", referenceId)
      case DisputeTarget.Reconciliation => reconRepo.findById(referenceId).ErrIfNotFound("reconciliation", referenceId)

    val now = clock.now
    val dispute = disputeRepo.insertReturning(
      Dispute(
        referenceId = referenceId,
        targetType  = targetType,
        category    = category,
        detail      = detail,
        createdAt   = Some(now),
        updatedAt   = Some(now)
      )
    )
    eventStore.publishEvent(DisputeRaised(disputeID = dispute.id))
    dispute

  def updateStatus(disputeId: ID, newStatus: DisputeStatus, resolutionNote: Option[String]): Txn[Dispute] =
    val dispute = disputeRepo.findById(disputeId).ErrIfNotFound("dispute", disputeId)
    if newStatus == DisputeStatus.Resolved && resolutionNote.forall(_.isBlank) then
      throw IllegalArgumentError("resolutionNote", "required when resolving a dispute")

    dispute.status = newStatus
    resolutionNote.foreach(n => dispute.resolutionNote = Some(n))
    dispute.updatedAt = Some(clock.now)
    disputeRepo.update(dispute)
    eventStore.publishEvent(DisputeStatusChanged(disputeID = dispute.id, newStatus = newStatus.toString))
    dispute
