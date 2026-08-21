package onparity.domain.service

import onparity.common.model.model.{ID, Money, Zero}
import onparity.domain.model.*
import onparity.domain.port.driven.*

class ManualReconciler(using
  val ledger:        Ledger,
  val movementRepo:  MovementRepo,
  val reconRepo:     ReconciliationRepo,
  val reconSpecRepo: ReconciliationSpecRepo,
  val eventStore:    EventStore,
  val clock:         ClockService
):

  def matchMovements(
    reconSpecId:    ID,
    inMovementIds:  Seq[ID],
    outMovementIds: Seq[ID],
    description:    String
  ): Txn[Reconciliation] =
    if inMovementIds.isEmpty then throw IllegalArgumentError("inMovementIds", "must select at least one in-movement")
    if outMovementIds.isEmpty then throw IllegalArgumentError("outMovementIds", "must select at least one out-movement")

    val reconSpec = reconSpecRepo.findById(reconSpecId).ErrIfNotFound("reconciliation spec", reconSpecId)

    val inMovements  = inMovementIds.map(id => movementRepo.findById(id).ErrIfNotFound("movement", id))
    val outMovements = outMovementIds.map(id => movementRepo.findById(id).ErrIfNotFound("movement", id))

    for m <- inMovements do
      if m.movementSpecId != reconSpec.inMovementSpecId then
        throw IllegalArgumentError("inMovementIds", s"movement ${m.id} does not belong to this spec's in-movement-spec")
      if m.reconciliationId.isDefined then
        throw IllegalArgumentError("inMovementIds", s"movement ${m.id} is already reconciled")

    for m <- outMovements do
      if m.movementSpecId != reconSpec.outMovementSpecId then
        throw IllegalArgumentError(
          "outMovementIds",
          s"movement ${m.id} does not belong to this spec's out-movement-spec"
        )
      if m.reconciliationId.isDefined then
        throw IllegalArgumentError("outMovementIds", s"movement ${m.id} is already reconciled")

    val balance = inMovements.map(_.amount).sum - outMovements.map(_.amount).sum
    val status  = if balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress

    val now = clock.now
    val reconciliation = Reconciliation(
      specId       = reconSpecId,
      appliedRules = "manual",
      description  = description,
      status       = status,
      balance      = balance,
      createdAt    = Some(now),
      updatedAt    = Some(now)
    )
    reconRepo.insert(reconciliation)

    ledger.reconcile(inMovements ++ outMovements, reconciliation.id, reconSpec.accountId)

    eventStore.publishEvent(ReconciliationManuallyMatched(reconciliationID = reconciliation.id))

    reconciliation

  def unmatch(reconciliationId: ID): Txn[Unit] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if reconciliation.status == ReconciliationStatus.Voided then
      throw IllegalStateError(s"reconciliation $reconciliationId is already voided")

    val reconSpec =
      reconSpecRepo.findById(reconciliation.specId).ErrIfNotFound("reconciliation spec", reconciliation.specId)
    val movements = movementRepo.findByReconciliationID(reconciliationId)

    ledger.unreconcile(movements, reconSpec.accountId)

    reconciliation.status = ReconciliationStatus.Voided
    reconRepo.update(reconciliation)

    eventStore.publishEvent(ReconciliationVoided(reconciliationID = reconciliationId))

  def writeOff(reconciliationId: ID, reason: String): Txn[Unit] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if reconciliation.status != ReconciliationStatus.InProgress then
      throw IllegalStateError(s"reconciliation $reconciliationId is not InProgress")

    reconciliation.adjustmentReason = Some(reason)
    reconciliation.status           = ReconciliationStatus.Final
    reconRepo.update(reconciliation)

    eventStore.publishEvent(ReconciliationWrittenOff(reconciliationID = reconciliationId, reason = reason))
