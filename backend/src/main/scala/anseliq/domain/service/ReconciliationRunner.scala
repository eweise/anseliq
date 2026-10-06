package anseliq.domain.service

import anseliq.common.model.ReconciliationInfo
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.scalalogging.StrictLogging
import net.logstash.logback.argument.StructuredArguments.kv

// Runs an account's reconciliation as a Reconciliation job: the background job poller hands it a
// job it has already claimed, and "Run reconciliation" (POST /account/:id/reconcile) starts one and
// runs it straight away. Not wrapped in one transaction: reconcileAccountResumable commits its own
// batches, so a large account doesn't need one unbounded transaction.
class ReconciliationRunner(
  accountRepo: AccountRepo,
  jobRepo:     JobRepo,
  jobManager:  JobManager,
  reconciler:  InMemoryReconciler,
  transactor:  Transactor
) extends StrictLogging:

  /** Submits a Reconciliation job for the account, marks it InProgress, and runs it now. */
  def reconcileNow(accountId: ID): Unit =
    val jobId = transact(transactor):
      accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $accountId not found"))
      val jobId = jobManager.submitJob(JobType.Reconciliation, accountId)
      val job   = jobRepo.findById(jobId).ErrIfNotFound("Job", jobId)
      jobRepo.update(job.copy(jobStatus = JobStatus.InProgress))
      jobId
    runJob(accountId, jobId)

  /** Reconciles the account under an existing job and records the result as that job's info. */
  def runJob(accountId: ID, jobId: ID): Unit =
    val account = transact(transactor):
      accountRepo.findById(accountId).getOrElse(throw NotFoundError(s"Account $accountId not found"))

    logger.debug("found account", kv("account_id", account.id))
    val result = reconciler.reconcileAccountResumable(account.id, jobId = Some(jobId))

    transact(transactor):
      val jobInfo = ReconciliationInfo(
        accountId                   = account.id.toString,
        accountName                 = account.name,
        reconciliationsCreatedCount = result.totalCount,
        processingTimeSeconds       = result.processingTimeSeconds
      )
      jobManager.completeJob(jobId, jobInfo)
