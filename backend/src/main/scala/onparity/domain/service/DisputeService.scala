package onparity.domain.service

import onparity.common.model.model.ID
import onparity.domain.model.*
import onparity.domain.port.driven.*

class DisputeService(using
  disputeRepo:  DisputeRepo,
  commentRepo:  DisputeCommentRepo,
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

  def addComment(disputeId: ID, body: Option[String], newStatus: Option[DisputeStatus]): Txn[DisputeComment] =
    val dispute = disputeRepo.findById(disputeId).ErrIfNotFound("dispute", disputeId)

    val bodyRequired = newStatus match
      case None                         => true
      case Some(DisputeStatus.Resolved) => true
      case Some(_)                      => false
    if bodyRequired && body.forall(_.isBlank) then
      throw IllegalArgumentError("body", "required for this entry")

    newStatus.foreach { s =>
      dispute.status = s
      dispute.updatedAt = Some(clock.now)
      disputeRepo.update(dispute)
      eventStore.publishEvent(DisputeStatusChanged(disputeID = dispute.id, newStatus = s.toString))
    }

    val comment = commentRepo.insertReturning(
      DisputeComment(disputeId = disputeId, body = body, newStatus = newStatus, createdAt = Some(clock.now))
    )
    eventStore.publishEvent(DisputeCommentAdded(disputeID = disputeId, commentID = comment.id))
    comment
