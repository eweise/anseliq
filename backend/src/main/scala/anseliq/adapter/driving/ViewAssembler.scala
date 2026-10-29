package anseliq.adapter.driving

import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

import java.util.UUID

// Builds the composite read-only payloads that join several repos: reconciliation, movement and
// exception detail views, movement search results with names, and an exception with the names of
// whatever it points at. Route classes call these instead of reaching into the repos themselves.
class ViewAssembler(
  ledgerRepo:           LedgerRepo,
  exceptionRepo:        ExceptionRepo,
  accountRepo:          AccountRepo,
  movementRepo:         MovementRepo,
  reconRepo:            ReconciliationRepo,
  movementSpecRepo:     MovementSpecRepo,
  fileFeedRepo:         FileFeedRepo,
  reconSpecRepo:        ReconciliationSpecRepo,
  exceptionCommentRepo: ExceptionCommentRepo,
  userRepo:             UserRepo
):

  def reconciliationDetail(reconId: ID): Txn[ReconciliationDetailPayload] =
    val recon = reconRepo.findById(reconId).ErrIfNotFound("reconciliation", reconId)
    val specAndAccount = recon.specId.map: specId =>
      val spec    = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
      val account = accountRepo.findById(spec.accountId).ErrIfNotFound("account", spec.accountId)
      (spec, account)
    val spec           = specAndAccount.map((s, account) => (s, account.name))
    val ledger         = specAndAccount.flatMap((_, account) => ledgerRepo.findById(account.ledgerId))
    val movements      = reconciliationMovementPayloads(recon, spec.map(_._1.accountId))
    val openExceptions = exceptionRepo.findOpenByReferenceId(reconId).map(exceptionPayloadWithNames)
    PayloadMapper.reconciliationDetailPayloadFromDomain(recon, spec, movements, ledger, openExceptions)

  def movementDetail(movementId: ID): Txn[MovementDetailPayload] =
    val m            = movementRepo.findById(movementId).ErrIfNotFound("movement", movementId)
    val outAccount   = accountRepo.findById(m.outAccountId).ErrIfNotFound("account", m.outAccountId)
    val inAccount    = accountRepo.findById(m.inAccountId).ErrIfNotFound("account", m.inAccountId)
    val movementSpec = movementSpecRepo.findById(m.movementSpecId).ErrIfNotFound("movement spec", m.movementSpecId)
    val detail       = PayloadMapper.movementDetailPayloadFromDomain(m, outAccount.name, inAccount.name, movementSpec.name)
    detail.copy(
      reconciliation = m.reconciliationId.flatMap(reconRepo.findById).map(PayloadMapper.reconFromDomain),
      ledger         = ledgerRepo.findById(inAccount.ledgerId).map(PayloadMapper.ledgerPayloadFromDomain),
      openExceptions = exceptionRepo.findOpenByReferenceId(m.id).map(exceptionPayloadWithNames)
    )

  def movementSearchResults(criteria: MovementSearchCriteria): Txn[Seq[MovementSearchResultPayload]] =
    val results = movementRepo.search(criteria)
    // Names resolved via the ledger's full account/movement-spec lists (both already
    // ledger-scoped, cheap fetches) rather than accountRepo.findAllById/
    // movementSpecRepo.findAllById on just the ids referenced by results — Magnum's default
    // findAllById binds a Postgres array parameter via JDBC's createArrayOf, which throws
    // ("Unable to find server array type for provided name OTHER") for a UUID id column against
    // this pgjdbc version. Fetching the whole ledger's accounts/specs sidesteps that
    // entirely and is simpler than working around the array-binding bug.
    val accountNames = accountRepo.findByLedger(criteria.ledgerId).map(a => a.id -> a.name).toMap
    val movSpecNames = movementSpecRepo.findByLedgerId(criteria.ledgerId).map(s => s.id -> s.name).toMap
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

  def exceptionDetail(exception: ExceptionCase): Txn[ExceptionDetailPayload] =
    val exceptionPayload = exceptionPayloadWithNames(exception)
    val movementPayload = exception.targetType match
      case ExceptionTarget.Movement =>
        movementRepo.findById(exception.referenceId).map(PayloadMapper.movementfromDomainModel)
      case ExceptionTarget.Reconciliation => None
      case ExceptionTarget.MovementSpec   => None
      case ExceptionTarget.FileFeed       => None
    val recon = exception.targetType match
      case ExceptionTarget.Reconciliation => reconRepo.findById(exception.referenceId)
      case ExceptionTarget.Movement       => None
      case ExceptionTarget.MovementSpec   => None
      case ExceptionTarget.FileFeed       => None
    val reconPayload = recon.map(PayloadMapper.reconFromDomain)
    val reconMovements = recon
      .map(r => reconciliationMovementPayloads(r, r.specId.flatMap(reconSpecRepo.findById).map(_.accountId)))
      .getOrElse(Seq.empty)
    val comments = exceptionCommentRepo
      .findByExceptionId(exception.id)
      .map(c => PayloadMapper.exceptionCommentPayloadFromDomain(c, userDisplay(c.newAssignedToUserId)))
    ExceptionDetailPayload(exceptionPayload, movementPayload, reconPayload, comments, reconMovements)

  def exceptionPayloadWithNames(exception: ExceptionCase): Txn[ExceptionPayload] =
    val (accountId, accountName, referenceLabel) = exceptionContext(exception)
    PayloadMapper.exceptionPayloadFromDomain(
      exception,
      accountId,
      accountName,
      referenceLabel,
      raisedByName   = userDisplay(exception.raisedByUserId),
      assignedToName = userDisplay(exception.assignedToUserId)
    )

  private def exceptionContext(exception: ExceptionCase): Txn[(String, String, String)] =
    exception.targetType match
      case ExceptionTarget.Movement =>
        val movement = movementRepo.findById(exception.referenceId).ErrIfNotFound("movement", exception.referenceId)
        val account  = accountRepo.findById(movement.inAccountId).ErrIfNotFound("account", movement.inAccountId)
        (account.id.toString, account.name, movement.referenceId.toString)
      case ExceptionTarget.Reconciliation =>
        // recon.specId is only ever None for a system-generated Reversed reconciliation, and
        // nothing ever raises an exception against one (exception-raising only happens on an InProgress
        // mismatch from matchMovements/streamMergeJoin, neither of which Bookkeeper.reverseMovement
        // goes through) — so this ErrIfNotFound is a correctness assertion, not an expected path.
        val recon     = reconRepo.findById(exception.referenceId).ErrIfNotFound("reconciliation", exception.referenceId)
        val specId    = recon.specId.ErrIfNotFound("reconciliation spec id", recon.id)
        val reconSpec = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
        val account   = accountRepo.findById(reconSpec.accountId).ErrIfNotFound("account", reconSpec.accountId)
        (account.id.toString, account.name, recon.description)
      case ExceptionTarget.MovementSpec =>
        val spec =
          movementSpecRepo.findById(exception.referenceId).ErrIfNotFound("movement spec", exception.referenceId)
        val account = accountRepo.findById(spec.inAccountId).ErrIfNotFound("account", spec.inAccountId)
        (account.id.toString, account.name, spec.name)
      case ExceptionTarget.FileFeed =>
        // A feed can route to several accounts (a multi-account BAI-style feed), so there's no
        // single account to attribute a FileMissing/UnroutedRecord exception to — accountId stays
        // blank rather than picking one arbitrarily.
        val feed = fileFeedRepo.findById(exception.referenceId).ErrIfNotFound("file feed", exception.referenceId)
        ("", feed.name, feed.name)

  private def userDisplay(userId: Option[UUID]): Txn[Option[String]] =
    userId.flatMap(id => userRepo.findById(id)).map(u => s"${u.firstName} ${u.lastName}")
