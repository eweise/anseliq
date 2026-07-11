package perfectpair.domain.service

import perfectpair.common.model.model.{ID, Money, Zero}
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*

class ManualReconciler(using
  val accountRepo:   AccountRepo,
  val movementRepo:  MovementRepo,
  val reconRepo:     ReconciliationRepo,
  val reconSpecRepo: ReconciliationSpecRepo,
  val eventStore:    EventStore
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
        throw IllegalArgumentError("outMovementIds", s"movement ${m.id} does not belong to this spec's out-movement-spec")
      if m.reconciliationId.isDefined then
        throw IllegalArgumentError("outMovementIds", s"movement ${m.id} is already reconciled")

    val balance = inMovements.map(_.amount).sum - outMovements.map(_.amount).sum
    val status  = if balance == Zero then ReconciliationStatus.Final else ReconciliationStatus.InProgress

    val reconciliation = Reconciliation(
      specId       = reconSpecId,
      appliedRules = "manual",
      description  = description,
      status       = status,
      balance      = balance
    )
    reconRepo.insert(reconciliation)

    var reconciledDelta: Money = Zero
    for m <- inMovements ++ outMovements do
      m.reconciliationId = Some(reconciliation.id)
      movementRepo.update(m)
      reconciledDelta = reconciledDelta + m.signedAmountFor(reconSpec.accountId)

    val account = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
    account.reconciledBalance   = account.reconciledBalance + reconciledDelta
    account.unreconciledBalance = account.unreconciledBalance - reconciledDelta
    accountRepo.update(account)

    eventStore.publishEvent(ReconciliationManuallyMatched(reconciliationID = reconciliation.id))

    reconciliation

  def unmatch(reconciliationId: ID): Txn[Unit] =
    val reconciliation = reconRepo.findById(reconciliationId).ErrIfNotFound("reconciliation", reconciliationId)
    if reconciliation.status == ReconciliationStatus.Voided then
      throw IllegalStateError(s"reconciliation $reconciliationId is already voided")

    val reconSpec = reconSpecRepo.findById(reconciliation.specId).ErrIfNotFound("reconciliation spec", reconciliation.specId)
    val movements  = movementRepo.findByReconciliationID(reconciliationId)

    var reconciledDelta: Money = Zero
    for m <- movements do
      reconciledDelta = reconciledDelta + m.signedAmountFor(reconSpec.accountId)
      m.reconciliationId = None
      movementRepo.update(m)

    val account = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
    account.reconciledBalance   = account.reconciledBalance - reconciledDelta
    account.unreconciledBalance = account.unreconciledBalance + reconciledDelta
    accountRepo.update(account)

    reconciliation.status = ReconciliationStatus.Voided
    reconRepo.update(reconciliation)

    eventStore.publishEvent(ReconciliationVoided(reconciliationID = reconciliationId))
