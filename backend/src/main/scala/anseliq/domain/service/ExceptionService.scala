package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

class ExceptionService(
  exceptionRepo:    ExceptionRepo,
  commentRepo:      ExceptionCommentRepo,
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
    targetType:     ExceptionTarget,
    category:       ExceptionCategory,
    detail:         Option[String],
    raisedByUserId: Option[ID] = None
  ): Txn[ExceptionCase] =
    requireValidTarget(category, targetType)

    targetType match
      case ExceptionTarget.Movement => movementRepo.findById(referenceId).ErrIfNotFound("movement", referenceId)
      case ExceptionTarget.Reconciliation =>
        reconRepo.findById(referenceId).ErrIfNotFound("reconciliation", referenceId)
      case ExceptionTarget.MovementSpec =>
        movementSpecRepo.findById(referenceId).ErrIfNotFound("movement spec", referenceId)
      case ExceptionTarget.FileFeed =>
        fileFeedRepo.findById(referenceId).ErrIfNotFound("file feed", referenceId)

    val now = clock.now
    // insert, not insertReturning: Magnum's insertReturning reads the row back via JDBC's
    // getGeneratedKeys(), which has a known pgjdbc quirk misreading a nullable uuid column
    // (ClassCastException: String -> UUID) — raisedByUserId is the first nullable-UUID field ever
    // passed through this path in this codebase. Sidestepped entirely by not reading back at all:
    // every field on `exception` below is already fully populated by application code (no DB-computed
    // defaults to pick up), so returning it directly is exactly equivalent to a successful readback.
    val exception = ExceptionCase(
      referenceId    = referenceId,
      targetType     = targetType,
      category       = category,
      detail         = detail,
      raisedByUserId = raisedByUserId,
      createdAt      = Some(now),
      updatedAt      = Some(now)
    )
    exceptionRepo.insert(exception)
    eventStore.publishEvent(ExceptionRaised(exceptionId = exception.id))
    exception

  /**
   * An exception the system raises on its own (no human actor): an AmountMismatch when a
   * reconciliation becomes Mismatched, or a FileFeed problem found during ingestion. Idempotent via
   * insertIfNoOpenException — a repeat raise for a (target, category) that already has an open
   * exception (a re-run, a later aging check, a feed still overdue next cycle) is a no-op returning
   * None. Same category/target check as `raise`; the target's existence isn't re-checked, since
   * every caller has just loaded or created it.
   */
  def raiseSystemException(
    referenceId: ID,
    targetType:  ExceptionTarget,
    category:    ExceptionCategory,
    detail:      String
  ): Txn[Option[ExceptionCase]] =
    requireValidTarget(category, targetType)
    val now = clock.now
    val raised = exceptionRepo.insertIfNoOpenException(
      ExceptionCase(
        referenceId    = referenceId,
        targetType     = targetType,
        category       = category,
        detail         = Some(detail),
        raisedByUserId = None,
        createdAt      = Some(now),
        updatedAt      = Some(now)
      )
    )
    raised.foreach(exception => eventStore.publishEvent(ExceptionRaised(exceptionId = exception.id)))
    raised

  /**
   * Resolves the open exception of `category` on a target, if there is one, because the problem it
   * tracked is gone: a mismatched reconciliation written off, unmatched or brought into balance, or
   * a missing file that has arrived. This is how the categories with an automaticResolution get
   * resolved, since addComment won't let a person do it. Recorded the same way a person resolving
   * it would be: a status change plus a comment explaining why, not silently closed.
   */
  def resolveAutomatically(referenceId: ID, category: ExceptionCategory, explanation: String): Txn[Unit] =
    exceptionRepo
      .findOpenByReferenceIdAndCategory(referenceId, category)
      .foreach: exception =>
        exception.status    = ExceptionStatus.Resolved
        exception.updatedAt = Some(clock.now)
        exceptionRepo.update(exception)
        eventStore.publishEvent(
          ExceptionStatusChanged(exceptionId = exception.id, newStatus = ExceptionStatus.Resolved.toString)
        )

        // insert, not insertReturning: the same pgjdbc nullable-uuid readback quirk as raise above
        // (ExceptionComment has newAssignedToUserId). Every field is already populated here.
        val comment = ExceptionComment(
          exceptionId = exception.id,
          body        = Some(explanation),
          newStatus   = Some(ExceptionStatus.Resolved),
          createdAt   = Some(clock.now)
        )
        commentRepo.insert(comment)
        eventStore.publishEvent(ExceptionCommentAdded(exceptionId = exception.id, commentId = comment.id))

  // The most recently created resolved exception of this category on a target, e.g. the one a
  // write-off resolved, so a later re-raise can link back to it.
  def latestResolved(referenceId: ID, category: ExceptionCategory): Txn[Option[ExceptionCase]] =
    exceptionRepo
      .findByReferenceIdAndCategory(referenceId, category)
      .filter(_.status == ExceptionStatus.Resolved)
      .lastOption

  /**
   * Connects two exceptions about the same problem with a comment on each thread linking to the
   * other — e.g. the exception a write-off resolved, and the new one raised when that write-off is
   * reversed — so someone reading either thread can follow the story to the other.
   */
  def crossLink(earlier: ExceptionCase, later: ExceptionCase, earlierNote: String, laterNote: String): Txn[Unit] =
    Seq(earlier -> (later, earlierNote), later -> (earlier, laterNote)).foreach { case (onException, (other, note)) =>
      val comment = ExceptionComment(
        exceptionId        = onException.id,
        body               = Some(note),
        relatedExceptionId = Some(other.id),
        createdAt          = Some(clock.now)
      )
      commentRepo.insert(comment)
      eventStore.publishEvent(ExceptionCommentAdded(exceptionId = onException.id, commentId = comment.id))
    }

  // "First Last" for a user, for system-written notes that name who did something.
  def displayName(userId: ID): Txn[Option[String]] =
    userRepo.findById(userId).map(u => s"${u.firstName} ${u.lastName}")

  private def requireValidTarget(category: ExceptionCategory, targetType: ExceptionTarget): Unit =
    if !category.validTargets.contains(targetType) then
      throw IllegalArgumentError(
        "targetType",
        s"$category cannot target $targetType (valid target(s): ${category.validTargets.mkString(", ")})"
      )

  def addComment(
    exceptionId:         ID,
    body:                Option[String],
    newStatus:           Option[ExceptionStatus],
    newAssignedToUserId: Option[ID] = None
  ): Txn[ExceptionComment] =
    val exception = exceptionRepo.findById(exceptionId).ErrIfNotFound("exception", exceptionId)

    // See ExceptionCategory.automaticResolution: the system owns these categories' whole lifecycle.
    // A person can move an open one between New/Investigating/Escalated, but only the actual fix
    // resolves it, and once resolved it stays resolved (nothing would ever resolve it again).
    if exception.category.automaticResolution.isDefined then
      val wasResolved = exception.status == ExceptionStatus.Resolved
      newStatus.filter(s => (s == ExceptionStatus.Resolved) != wasResolved).foreach { _ =>
        val why =
          if wasResolved then
            s"${exception.category} exceptions are resolved by the system and can't be reopened. " +
              exception.category.insteadOfReopening.getOrElse("")
          else
            s"${exception.category} exceptions can't be resolved by hand. " +
              exception.category.automaticResolution.getOrElse("")
        throw IllegalArgumentError("newStatus", why.trim)
      }

    // A resolved exception has no work left for anyone to own, so it can't be assigned — including in
    // the same entry that resolves it. Reopening (where the category allows it) and assigning in
    // one entry is fine, since the exception is open again afterwards.
    if newAssignedToUserId.isDefined && newStatus.getOrElse(exception.status) == ExceptionStatus.Resolved then
      throw IllegalArgumentError("newAssignedToUserId", "a resolved exception can't be assigned")

    val bodyRequired = (newStatus, newAssignedToUserId) match
      case (None, None)                        => true // plain comment, must say something
      case (Some(ExceptionStatus.Resolved), _) => true
      case _                                   => false
    if bodyRequired && body.forall(_.isBlank) then throw IllegalArgumentError("body", "required for this entry")

    newAssignedToUserId.foreach { userId =>
      val assignee = userRepo.findById(userId).ErrIfNotFound("user", userId)
      if assignee.role == UserRole.Viewer then
        throw IllegalArgumentError("newAssignedToUserId", "cannot assign an exception to a Viewer")
      exception.assignedToUserId = Some(userId)
      exception.updatedAt        = Some(clock.now)
      exceptionRepo.update(exception)
    }

    newStatus.foreach { s =>
      exception.status    = s
      exception.updatedAt = Some(clock.now)
      exceptionRepo.update(exception)
      eventStore.publishEvent(ExceptionStatusChanged(exceptionId = exception.id, newStatus = s.toString))
    }

    // insert, not insertReturning: same pgjdbc nullable-uuid readback quirk documented on
    // ExceptionService.raise above — newAssignedToUserId is a second nullable-UUID field on this
    // row. Every field is already fully populated by application code, so returning it directly
    // is exactly equivalent to a successful readback.
    val comment = ExceptionComment(
      exceptionId         = exceptionId,
      body                = body,
      newStatus           = newStatus,
      newAssignedToUserId = newAssignedToUserId,
      createdAt           = Some(clock.now)
    )
    commentRepo.insert(comment)
    eventStore.publishEvent(ExceptionCommentAdded(exceptionId = exceptionId, commentId = comment.id))
    comment
