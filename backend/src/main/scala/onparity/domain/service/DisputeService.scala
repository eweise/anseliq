package onparity.domain.service

import onparity.common.model.model.ID
import onparity.domain.model.*
import onparity.domain.port.driven.*

class DisputeService(using
  disputeRepo:      DisputeRepo,
  commentRepo:      DisputeCommentRepo,
  movementRepo:     MovementRepo,
  reconRepo:        ReconciliationRepo,
  movementSpecRepo: MovementSpecRepo,
  fileFeedRepo:     FileFeedRepo,
  userRepo:         UserRepo,
  eventStore:       EventStore,
  clock:            ClockService
):

  def raise(
    referenceId:    ID,
    targetType:     DisputeTarget,
    category:       DisputeCategory,
    detail:         Option[String],
    raisedByUserId: Option[ID] = None
  ): Txn[Dispute] =
    if !category.validTargets.contains(targetType) then
      throw IllegalArgumentError(
        "targetType",
        s"$category cannot target $targetType (valid target(s): ${category.validTargets.mkString(", ")})"
      )

    targetType match
      case DisputeTarget.Movement       => movementRepo.findById(referenceId).ErrIfNotFound("movement", referenceId)
      case DisputeTarget.Reconciliation => reconRepo.findById(referenceId).ErrIfNotFound("reconciliation", referenceId)
      case DisputeTarget.MovementSpec =>
        movementSpecRepo.findById(referenceId).ErrIfNotFound("movement spec", referenceId)
      case DisputeTarget.FileFeed =>
        fileFeedRepo.findById(referenceId).ErrIfNotFound("file feed", referenceId)

    val now = clock.now
    // insert, not insertReturning: Magnum's insertReturning reads the row back via JDBC's
    // getGeneratedKeys(), which has a known pgjdbc quirk misreading a nullable uuid column
    // (ClassCastException: String -> UUID) — raisedByUserId is the first nullable-UUID field ever
    // passed through this path in this codebase. Sidestepped entirely by not reading back at all:
    // every field on `dispute` below is already fully populated by application code (no DB-computed
    // defaults to pick up), so returning it directly is exactly equivalent to a successful readback.
    val dispute = Dispute(
      referenceId    = referenceId,
      targetType     = targetType,
      category       = category,
      detail         = detail,
      raisedByUserId = raisedByUserId,
      createdAt      = Some(now),
      updatedAt      = Some(now)
    )
    disputeRepo.insert(dispute)
    eventStore.publishEvent(DisputeRaised(disputeID = dispute.id))
    dispute

  def addComment(
    disputeId:           ID,
    body:                Option[String],
    newStatus:           Option[DisputeStatus],
    newAssignedToUserId: Option[ID] = None
  ): Txn[DisputeComment] =
    val dispute = disputeRepo.findById(disputeId).ErrIfNotFound("dispute", disputeId)

    val bodyRequired = (newStatus, newAssignedToUserId) match
      case (None, None)                    => true // plain comment, must say something
      case (Some(DisputeStatus.Resolved), _) => true
      case _                                => false
    if bodyRequired && body.forall(_.isBlank) then throw IllegalArgumentError("body", "required for this entry")

    newAssignedToUserId.foreach { userId =>
      val assignee = userRepo.findById(userId).ErrIfNotFound("user", userId)
      if assignee.role == UserRole.Viewer then
        throw IllegalArgumentError("newAssignedToUserId", "cannot assign a dispute to a Viewer")
      dispute.assignedToUserId = Some(userId)
      dispute.updatedAt        = Some(clock.now)
      disputeRepo.update(dispute)
    }

    newStatus.foreach { s =>
      dispute.status    = s
      dispute.updatedAt = Some(clock.now)
      disputeRepo.update(dispute)
      eventStore.publishEvent(DisputeStatusChanged(disputeID = dispute.id, newStatus = s.toString))
    }

    // insert, not insertReturning: same pgjdbc nullable-uuid readback quirk documented on
    // DisputeService.raise above — newAssignedToUserId is a second nullable-UUID field on this
    // row. Every field is already fully populated by application code, so returning it directly
    // is exactly equivalent to a successful readback.
    val comment = DisputeComment(
      disputeId           = disputeId,
      body                = body,
      newStatus           = newStatus,
      newAssignedToUserId = newAssignedToUserId,
      createdAt           = Some(clock.now)
    )
    commentRepo.insert(comment)
    eventStore.publishEvent(DisputeCommentAdded(disputeID = disputeId, commentID = comment.id))
    comment
