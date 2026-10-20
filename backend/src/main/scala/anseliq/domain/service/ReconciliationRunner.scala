package anseliq.domain.service

import anseliq.common.model.ReconciliationInfo
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.scalalogging.StrictLogging
import net.logstash.logback.argument.StructuredArguments.kv

// Runs an account's reconciliation as a Reconciliation job: the background worker hands it a job
// it has already claimed, and "Run reconciliation" (POST /account/:id/reconcile) starts one and
// runs it straight away. Not wrapped in one transaction: reconcileAccountResumable commits its own
// batches, so a large account doesn't need one unbounded transaction. Also decides which accounts
// newly ingested movements need reconciled.
class ReconciliationRunner(
  accountRepo:      AccountRepo,
  movementSpecRepo: MovementSpecRepo,
  jobManager:       JobManager,
  reconciler:       InMemoryReconciler,
  transactor:       Transactor,
  // Whether a reconciliation queues a SuggestMatches job for what it left unmatched.
  suggestMatches: Boolean = false
) extends StrictLogging:

  /**
   * Queues a Reconciliation job for each Recon account on either side of these movement specs,
   * after an ingestion added movements under them. A job already queued for the account absorbs
   * this one.
   */
  def queueReconciliations(movementSpecIds: Set[ID]): Txn[Unit] =
    val accountIds = movementSpecIds.flatMap: specId =>
      val spec = movementSpecRepo.findById(specId).ErrIfNotFound("movementSpec", specId)
      Set(spec.inAccountId, spec.outAccountId)
    accountIds
      .filter(accountId => accountRepo.findById(accountId).exists(_.accountType == AccountType.Recon))
      .foreach(accountId => jobManager.submitJob(JobType.Reconciliation, accountId))

  /**
   * Submits a Reconciliation job for the account, claims it, and runs it now. An IllegalStateError
   * (409) if the account's reconciliation is already running. A failed run marks the job in error,
   * as the background poller does, rather than leaving it InProgress for good, and then rethrows so
   * the caller still sees the failure.
   */
  def reconcileNow(accountId: ID): Unit =
    val jobId = transact(transactor):
      accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $accountId not found"))
      val jobId = jobManager.submitJob(JobType.Reconciliation, accountId)
      jobManager.claimJob(jobId)
      jobId
    try
      val jobInfo = jobManager.withLease(jobId)(runJob(accountId, jobId))
      transact(transactor)(jobManager.completeJob(jobId, jobInfo))
    catch
      case ex: Exception =>
        logger.error("reconciliation job failed", kv("job_id", jobId), ex)
        transact(transactor)(jobManager.markJobInError(jobId, ex))
        throw ex

  /**
   * Reconciles the account under an existing, claimed job, returning the result to complete it
   * with, then queues secondary matching of whatever it left unmatched if that's turned on.
   */
  def runJob(accountId: ID, jobId: ID): ReconciliationInfo =
    val account = transact(transactor):
      accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $accountId not found"))

    logger.debug("found account", kv("account_id", account.id))
    val result = reconciler.reconcileAccountResumable(account.id, jobId = Some(jobId))
    if suggestMatches then transact(transactor)(jobManager.submitJob(JobType.SuggestMatches, account.id))

    ReconciliationInfo(
      accountId                   = account.id.toString,
      accountName                 = account.name,
      reconciliationsCreatedCount = result.totalCount,
      processingTimeSeconds       = result.processingTimeSeconds
    )
