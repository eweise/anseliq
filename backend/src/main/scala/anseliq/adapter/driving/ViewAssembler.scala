package anseliq.adapter.driving

import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

import java.util.UUID

// Builds the composite read-only payloads that join several repos: reconciliation, movement and
// dispute detail views, movement search results with names, and a dispute with the names of
// whatever it points at. Route classes call these instead of reaching into the repos themselves.
class ViewAssembler(
  fundFlowConfigRepo: FundFlowConfigRepo,
  disputeRepo:        DisputeRepo,
  accountRepo:        AccountRepo,
  movementRepo:       MovementRepo,
  reconRepo:          ReconciliationRepo,
  movementSpecRepo:   MovementSpecRepo,
  fileFeedRepo:       FileFeedRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  disputeCommentRepo: DisputeCommentRepo,
  userRepo:           UserRepo
):

  def reconciliationDetail(reconId: ID): Txn[ReconciliationDetailPayload] =
    val recon = reconRepo.findById(reconId).ErrIfNotFound("reconciliation", reconId)
    val specAndAccount = recon.specId.map: specId =>
      val spec    = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
      val account = accountRepo.findById(spec.accountId).ErrIfNotFound("account", spec.accountId)
      (spec, account)
    val spec         = specAndAccount.map((s, account) => (s, account.name))
    val fundFlow     = specAndAccount.flatMap((_, account) => fundFlowConfigRepo.findById(account.fundFlowId))
    val movements    = reconciliationMovementPayloads(recon, spec.map(_._1.accountId))
    val openDisputes = disputeRepo.findOpenByReferenceId(reconId).map(disputePayloadWithNames)
    PayloadMapper.reconciliationDetailPayloadFromDomain(recon, spec, movements, fundFlow, openDisputes)

  def movementDetail(movementId: ID): Txn[MovementDetailPayload] =
    val m            = movementRepo.findById(movementId).ErrIfNotFound("movement", movementId)
    val outAccount   = accountRepo.findById(m.outAccountId).ErrIfNotFound("account", m.outAccountId)
    val inAccount    = accountRepo.findById(m.inAccountId).ErrIfNotFound("account", m.inAccountId)
    val movementSpec = movementSpecRepo.findById(m.movementSpecId).ErrIfNotFound("movement spec", m.movementSpecId)
    val detail       = PayloadMapper.movementDetailPayloadFromDomain(m, outAccount.name, inAccount.name, movementSpec.name)
    detail.copy(
      reconciliation = m.reconciliationId.flatMap(reconRepo.findById).map(PayloadMapper.reconFromDomain),
      fundFlow       = fundFlowConfigRepo.findById(inAccount.fundFlowId).map(PayloadMapper.fundFlowPayloadFromDomain),
      openDisputes   = disputeRepo.findOpenByReferenceId(m.id).map(disputePayloadWithNames)
    )

  def movementSearchResults(criteria: MovementSearchCriteria): Txn[Seq[MovementSearchResultPayload]] =
    val results = movementRepo.search(criteria)
    // Names resolved via the fund flow's full account/movement-spec lists (both already
    // fund-flow-scoped, cheap fetches) rather than accountRepo.findAllById/
    // movementSpecRepo.findAllById on just the ids referenced by results — Magnum's default
    // findAllById binds a Postgres array parameter via JDBC's createArrayOf, which throws
    // ("Unable to find server array type for provided name OTHER") for a UUID id column against
    // this pgjdbc version. Fetching the whole fund flow's accounts/specs sidesteps that
    // entirely and is simpler than working around the array-binding bug.
    val accountNames = accountRepo.findByFundFlow(criteria.fundFlowId).map(a => a.id -> a.name).toMap
    val movSpecNames = movementSpecRepo.findByFundFlowId(criteria.fundFlowId).map(s => s.id -> s.name).toMap
    results.map(m => PayloadMapper.movementSearchResultPayloadFromDomain(m, accountNames, movSpecNames))

  // A reconciliation's movements as payload rows, each movement's accounts' names looked up once
  // per distinct account rather than once per movement.
  private def reconciliationMovementPayloads(
    recon:          Reconciliation,
    reconAccountId: Option[ID]
  ): Txn[Seq[ReconciliationMovementPayload]] =
    val movements = movementRepo.findByReconciliationID(recon.id)
    val accountNames = movements
      .flatMap(m => Seq(m.inAccountId, m.outAccountId))
      .distinct
      .map(id => id -> accountRepo.findById(id).map(_.name).getOrElse(id.toString))
      .toMap
    movements.map(PayloadMapper.reconciliationMovementFromDomain(_, reconAccountId, accountNames))

  def disputeDetail(dispute: Dispute): Txn[DisputeDetailPayload] =
    val disputePayload = disputePayloadWithNames(dispute)
    val movementPayload = dispute.targetType match
      case DisputeTarget.Movement       => movementRepo.findById(dispute.referenceId).map(PayloadMapper.movementfromDomainModel)
      case DisputeTarget.Reconciliation => None
      case DisputeTarget.MovementSpec   => None
      case DisputeTarget.FileFeed       => None
    val recon = dispute.targetType match
      case DisputeTarget.Reconciliation => reconRepo.findById(dispute.referenceId)
      case DisputeTarget.Movement       => None
      case DisputeTarget.MovementSpec   => None
      case DisputeTarget.FileFeed       => None
    val reconPayload = recon.map(PayloadMapper.reconFromDomain)
    val reconMovements = recon
      .map(r => reconciliationMovementPayloads(r, r.specId.flatMap(reconSpecRepo.findById).map(_.accountId)))
      .getOrElse(Seq.empty)
    val comments = disputeCommentRepo
      .findByDisputeId(dispute.id)
      .map(c => PayloadMapper.disputeCommentPayloadFromDomain(c, userDisplay(c.newAssignedToUserId)))
    DisputeDetailPayload(disputePayload, movementPayload, reconPayload, comments, reconMovements)

  def disputePayloadWithNames(dispute: Dispute): Txn[DisputePayload] =
    val (accountId, accountName, referenceLabel) = disputeContext(dispute)
    PayloadMapper.disputePayloadFromDomain(
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
