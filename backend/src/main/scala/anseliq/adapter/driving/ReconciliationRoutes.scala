package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.adapter.driving.ApiRoutes.requireAdmin
import anseliq.common.util.JwtHelper
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class ReconciliationRoutes(
  transactor:         Transactor,
  views:              ViewAssembler,
  fundFlowConfigRepo: FundFlowConfigRepo,
  accountRepo:        AccountRepo,
  reconRepo:          ReconciliationRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  disputeRepo:        DisputeRepo,
  jobRepo:            JobRepo,
  jobManager:         JobManager,
  reconciler:         InMemoryReconciler,
  manualReconciler:   ManualReconciler
) extends ApiRoutes:

  @cask.route(s"${HttpPath.reconSpec}/:reconSpecID/reconciliations", methods = HttpCmd.GET)
  def movementsByReconciliationSpec(reconSpecID: String): String =
    transact(transactor):
      val reconciliations = reconRepo.findByReconSpecID(UUID.fromString(reconSpecID))
      val payload         = reconciliations.map(Payload.reconFromDomain)
      write(payload)

  @cask.route(s"${HttpPath.account}/:id/reconciliations", methods = HttpCmd.GET)
  def reconciliationsByAccount(id: String, limit: Int = 100, status: Option[String] = None): String =
    val statusFilter = status.map(s =>
      ReconciliationStatus.values
        .find(_.toString == s)
        .getOrElse(throw IllegalArgumentError("status", s"unknown reconciliation status: $s"))
    )
    transact(transactor):
      val reconciliations = reconRepo.findByAccountID(UUID.fromString(id), limit, statusFilter)
      write(reconciliations.map(Payload.reconFromDomain))

  // Keyed by ReconciliationStatus name ("Mismatched", "InProgress", ...), statuses with none omitted.
  @cask.route(s"${HttpPath.account}/:id/reconciliation-counts", methods = HttpCmd.GET)
  def reconciliationCountsByAccount(id: String): String =
    transact(transactor):
      write(reconRepo.countByStatusForAccountID(UUID.fromString(id)).map((s, n) => s.toString -> n))

  @cask.route(s"${HttpPath.account}/:id/reconcile", methods = HttpCmd.POST)
  def reoncileAccount(id: String, request: cask.Request): String =
    val accountId = UUID.fromString(id)
    val (account, job) = transact(transactor):
      val account = accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $id not found"))
      val jobId   = jobManager.submitJob(JobType.Reconciliation, accountId)
      val job     = jobRepo.findById(jobId).ErrIfNotFound("Job", jobId)
      jobRepo.update(job.copy(jobStatus = JobStatus.InProgress))
      (account, job)

    val reconcilationResult = reconciler.reconcileAccountResumable(account.id, jobId = Some(job.id))

    transact(transactor):
      val jobInfo = ReconciliationInfo(
        accountId                   = account.id.toString,
        accountName                 = account.name,
        reconciliationsCreatedCount = reconcilationResult.totalCount,
        processingTimeSeconds       = reconcilationResult.processingTimeSeconds
      )
      jobManager.completeJob(job.id, jobInfo)
    write("reconciled")

  @cask.route(s"${HttpPath.reconSpec}/:id/manual-match", methods = HttpCmd.POST)
  def manualMatch(id: String, request: cask.Request): String =
    val payload = read[ManualMatchRequest](request.text())
    transact(transactor):
      val reconciliation = manualReconciler.matchMovements(
        UUID.fromString(id),
        payload.inMovementIds.map(UUID.fromString),
        payload.outMovementIds.map(UUID.fromString),
        payload.description
      )
      write(Payload.reconFromDomain(reconciliation))

  @cask.route(s"${HttpPath.reconciliation}/:id", methods = HttpCmd.GET)
  def getReconciliationDetail(id: String): String =
    transact(transactor):
      val reconId = UUID.fromString(id)
      val recon   = reconRepo.findById(reconId).ErrIfNotFound("reconciliation", reconId)
      val specAndAccount = recon.specId.map: specId =>
        val spec    = reconSpecRepo.findById(specId).ErrIfNotFound("reconciliation spec", specId)
        val account = accountRepo.findById(spec.accountId).ErrIfNotFound("account", spec.accountId)
        (spec, account)
      val spec         = specAndAccount.map((s, account) => (s, account.name))
      val fundFlow     = specAndAccount.flatMap((_, account) => fundFlowConfigRepo.findById(account.fundFlowId))
      val movements    = views.reconciliationMovementPayloads(recon, spec.map(_._1.accountId))
      val openDisputes = disputeRepo.findOpenByReferenceId(reconId).map(views.disputePayloadWithNames)
      write(Payload.reconciliationDetailPayloadFromDomain(recon, spec, movements, fundFlow, openDisputes))

  @cask.route(s"${HttpPath.reconciliation}/:id/unmatch", methods = HttpCmd.POST)
  def unmatchReconciliation(id: String): String =
    transact(transactor):
      manualReconciler.unmatch(UUID.fromString(id))
    write("voided")

  @cask.route(s"${HttpPath.reconciliation}/:id/write-off", methods = HttpCmd.POST)
  def writeOffReconciliation(id: String, request: cask.Request): String =
    val payload = read[WriteOffRequest](request.text())
    transact(transactor):
      manualReconciler.writeOff(UUID.fromString(id), UUID.fromString(payload.movementId), payload.reason)
    write("written-off")

  @cask.route(s"${HttpPath.reconciliation}/:id/reverse-write-off", methods = HttpCmd.POST)
  def reverseWriteOff(id: String, request: cask.Request)(currentUser: JwtHelper.UserClaim): String =
    requireAdmin(currentUser)
    val payload = read[ReverseWriteOffRequest](request.text())
    transact(transactor):
      manualReconciler.reverseWriteOff(UUID.fromString(id), payload.reason, Some(UUID.fromString(currentUser.userId)))
    write("write-off-reversed")

  initialize()
