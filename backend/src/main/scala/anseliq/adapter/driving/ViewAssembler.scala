package anseliq.adapter.driving

import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

import java.util.UUID

// Builds the composite payloads more than one route class returns: a dispute with the names of
// whatever it points at, a dispute's detail view, and a reconciliation's movements.
class ViewAssembler(
  accountRepo:        AccountRepo,
  movementRepo:       MovementRepo,
  reconRepo:          ReconciliationRepo,
  movementSpecRepo:   MovementSpecRepo,
  fileFeedRepo:       FileFeedRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  disputeCommentRepo: DisputeCommentRepo,
  userRepo:           UserRepo
):

  // A reconciliation's movements as payload rows, each movement's accounts' names looked up once
  // per distinct account rather than once per movement.
  def reconciliationMovementPayloads(
    recon:          Reconciliation,
    reconAccountId: Option[ID]
  ): Txn[Seq[ReconciliationMovementPayload]] =
    val movements = movementRepo.findByReconciliationID(recon.id)
    val accountNames = movements
      .flatMap(m => Seq(m.inAccountId, m.outAccountId))
      .distinct
      .map(id => id -> accountRepo.findById(id).map(_.name).getOrElse(id.toString))
      .toMap
    movements.map(Payload.reconciliationMovementFromDomain(_, reconAccountId, accountNames))

  def disputeDetail(dispute: Dispute): Txn[DisputeDetailPayload] =
    val disputePayload = disputePayloadWithNames(dispute)
    val movementPayload = dispute.targetType match
      case DisputeTarget.Movement       => movementRepo.findById(dispute.referenceId).map(Payload.movementfromDomainModel)
      case DisputeTarget.Reconciliation => None
      case DisputeTarget.MovementSpec   => None
      case DisputeTarget.FileFeed       => None
    val recon = dispute.targetType match
      case DisputeTarget.Reconciliation => reconRepo.findById(dispute.referenceId)
      case DisputeTarget.Movement       => None
      case DisputeTarget.MovementSpec   => None
      case DisputeTarget.FileFeed       => None
    val reconPayload = recon.map(Payload.reconFromDomain)
    val reconMovements = recon
      .map(r => reconciliationMovementPayloads(r, r.specId.flatMap(reconSpecRepo.findById).map(_.accountId)))
      .getOrElse(Seq.empty)
    val comments = disputeCommentRepo
      .findByDisputeId(dispute.id)
      .map(c => Payload.disputeCommentPayloadFromDomain(c, userDisplay(c.newAssignedToUserId)))
    DisputeDetailPayload(disputePayload, movementPayload, reconPayload, comments, reconMovements)

  def disputePayloadWithNames(dispute: Dispute): Txn[DisputePayload] =
    val (accountId, accountName, referenceLabel) = disputeContext(dispute)
    Payload.disputePayloadFromDomain(
      dispute,
      accountId,
      accountName,
      referenceLabel,
      raisedByName   = userDisplay(dispute.raisedByUserId),
      assignedToName = userDisplay(dispute.assignedToUserId)
    )

  private def disputeContext(dispute: Dispute): Txn[(String, String, String)] =
    dispute.targetType match
      case DisputeTarget.Movement =>
        val movement = movementRepo.findById(dispute.referenceId).ErrIfNotFound("movement", dispute.referenceId)
        val account  = accountRepo.findById(movement.inAccountId).ErrIfNotFound("account", movement.inAccountId)
        (account.id.toString, account.name, movement.referenceId.toString)
      case DisputeTarget.Reconciliation =>
        // recon.specId is only ever None for a system-generated Reversed reconciliation, and
        // nothing ever raises a dispute against one (dispute-raising only happens on an InProgress
        // mismatch from matchMovements/streamMergeJoin, neither of which Ledger.reverseMovement
        // goes through) — so this ErrIfNotFound is a correctness assertion, not an expected path.
        val recon     = reconRepo.findById(dispute.referenceId).ErrIfNotFound("reconciliation", dispute.referenceId)
        val specId    = recon.specId.ErrIfNotFound("reconciliation spec id", recon.id)
        val reconSpec = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
        val account   = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
        (account.id.toString, account.name, recon.description)
      case DisputeTarget.MovementSpec =>
        val spec    = movementSpecRepo.findById(dispute.referenceId).ErrIfNotFound("movement spec", dispute.referenceId)
        val account = accountRepo.findById(spec.inAccountId).ErrIfNotFound("account", spec.inAccountId)
        (account.id.toString, account.name, spec.name)
      case DisputeTarget.FileFeed =>
        // A feed can route to several accounts (a multi-account BAI-style feed), so there's no
        // single account to attribute a FileMissing/UnroutedRecord dispute to — accountId stays
        // blank rather than picking one arbitrarily.
        val feed = fileFeedRepo.findById(dispute.referenceId).ErrIfNotFound("file feed", dispute.referenceId)
        ("", feed.name, feed.name)

  private def userDisplay(userId: Option[UUID]): Txn[Option[String]] =
    userId.flatMap(id => userRepo.findById(id)).map(u => s"${u.firstName} ${u.lastName}")
