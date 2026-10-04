package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

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
    requireValidTarget(category, targetType)

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

  /**
   * A dispute the system raises on its own (no human actor): an AmountMismatch when a reconciliation
   * becomes Mismatched, or a FileFeed problem found during ingestion. Idempotent via
   * insertIfNoOpenDispute — a repeat raise for a (target, category) that already has an open
   * dispute (a re-run, a later aging check, a feed still overdue next cycle) is a no-op returning
   * None. Same category/target check as `raise`; the target's existence isn't re-checked, since
   * every caller has just loaded or created it.
   */
  def raiseSystemDispute(
    referenceId: ID,
    targetType:  DisputeTarget,
    category:    DisputeCategory,
    detail:      String
  ): Txn[Option[Dispute]] =
    requireValidTarget(category, targetType)
    val now = clock.now
    val raised = disputeRepo.insertIfNoOpenDispute(
      Dispute(
        referenceId    = referenceId,
        targetType     = targetType,
        category       = category,
        detail         = Some(detail),
        raisedByUserId = None,
        createdAt      = Some(now),
        updatedAt      = Some(now)
      )
    )
    raised.foreach(dispute => eventStore.publishEvent(DisputeRaised(disputeID = dispute.id)))
    raised

  /**
   * Resolves the open dispute of `category` on a target, if there is one, because the problem it
   * tracked is gone: a mismatched reconciliation written off, unmatched or brought into balance, or
   * a missing file that has arrived. This is how the categories with an automaticResolution get
   * resolved, since addComment won't let a person do it. Recorded the same way a person resolving
   * it would be: a status change plus a comment explaining why, not silently closed.
   */
  def resolveAutomatically(referenceId: ID, category: DisputeCategory, explanation: String): Txn[Unit] =
    disputeRepo
      .findOpenByReferenceIdAndCategory(referenceId, category)
      .foreach: dispute =>
        dispute.status    = DisputeStatus.Resolved
        dispute.updatedAt = Some(clock.now)
        disputeRepo.update(dispute)
        eventStore.publishEvent(
          DisputeStatusChanged(disputeID = dispute.id, newStatus = DisputeStatus.Resolved.toString)
        )

        // insert, not insertReturning: the same pgjdbc nullable-uuid readback quirk as raise above
        // (DisputeComment has newAssignedToUserId). Every field is already populated here.
        val comment = DisputeComment(
          disputeId = dispute.id,
          body      = Some(explanation),
          newStatus = Some(DisputeStatus.Resolved),
          createdAt = Some(clock.now)
        )
        commentRepo.insert(comment)
        eventStore.publishEvent(DisputeCommentAdded(disputeID = dispute.id, commentID = comment.id))

  // The most recently created resolved dispute of this category on a target, e.g. the one a
  // write-off resolved, so a later re-raise can link back to it.
  def latestResolved(referenceId: ID, category: DisputeCategory): Txn[Option[Dispute]] =
    disputeRepo.findByReferenceIdAndCategory(referenceId, category).filter(_.status == DisputeStatus.Resolved).lastOption

  /**
   * Connects two disputes about the same problem with a comment on each thread linking to the other
   * — e.g. the dispute a write-off resolved, and the new one raised when that write-off is
   * reversed — so someone reading either thread can follow the story to the other.
   */
  def crossLink(earlier: Dispute, later: Dispute, earlierNote: String, laterNote: String): Txn[Unit] =
    Seq(earlier -> (later, earlierNote), later -> (earlier, laterNote)).foreach { case (onDispute, (other, note)) =>
      val comment = DisputeComment(
        disputeId        = onDispute.id,
        body             = Some(note),
        relatedDisputeId = Some(other.id),
        createdAt        = Some(clock.now)
      )
      commentRepo.insert(comment)
      eventStore.publishEvent(DisputeCommentAdded(disputeID = onDispute.id, commentID = comment.id))
    }

  // "First Last" for a user, for system-written notes that name who did something.
  def displayName(userId: ID): Txn[Option[String]] =
    userRepo.findById(userId).map(u => s"${u.firstName} ${u.lastName}")

  private def requireValidTarget(category: DisputeCategory, targetType: DisputeTarget): Unit =
    if !category.validTargets.contains(targetType) then
      throw IllegalArgumentError(
        "targetType",
        s"$category cannot target $targetType (valid target(s): ${category.validTargets.mkString(", ")})"
      )

  def addComment(
    disputeId:           ID,
    body:                Option[String],
    newStatus:           Option[DisputeStatus],
    newAssignedToUserId: Option[ID] = None
  ): Txn[DisputeComment] =
    val dispute = disputeRepo.findById(disputeId).ErrIfNotFound("dispute", disputeId)

    // See DisputeCategory.automaticResolution: the system owns these categories' whole lifecycle.
    // A person can move an open one between New/Investigating/Escalated, but only the actual fix
    // resolves it, and once resolved it stays resolved (nothing would ever resolve it again).
    if dispute.category.automaticResolution.isDefined then
      val wasResolved = dispute.status == DisputeStatus.Resolved
      newStatus.filter(s => (s == DisputeStatus.Resolved) != wasResolved).foreach { _ =>
        val why =
          if wasResolved then
            s"${dispute.category} disputes are resolved by the system and can't be reopened. " +
              dispute.category.insteadOfReopening.getOrElse("")
          else
            s"${dispute.category} disputes can't be resolved by hand. " +
              dispute.category.automaticResolution.getOrElse("")
        throw IllegalArgumentError("newStatus", why.trim)
      }

    // A resolved dispute has no work left for anyone to own, so it can't be assigned — including in
    // the same entry that resolves it. Reopening (where the category allows it) and assigning in
    // one entry is fine, since the dispute is open again afterwards.
    if newAssignedToUserId.isDefined && newStatus.getOrElse(dispute.status) == DisputeStatus.Resolved then
      throw IllegalArgumentError("newAssignedToUserId", "a resolved dispute can't be assigned")

    val bodyRequired = (newStatus, newAssignedToUserId) match
      case (None, None)                      => true // plain comment, must say something
      case (Some(DisputeStatus.Resolved), _) => true
      case _                                 => false
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
