package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, JobRepoLive}
import perfectpair.common.model.ReconciliationInfo
import perfectpair.domain.model.JobStatus.InProgress
import perfectpair.domain.model.JobType.Reconciliation
import perfectpair.domain.model.{IllegalArgumentError, IllegalStateError, Job, JobType}
import perfectpair.testing

import java.util.UUID

class JobManagerSuite extends munit.FunSuite:
  private val dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  private val transactor: Transactor = Transactor(dataSource)
  private val clock      = ClockService()
  private val jobRepo    = JobRepoLive()
  private val jobManager = JobManager(jobRepo, clock, dataSource)

  test("submit and complete job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val jobId       = jobManager.submitJob(Reconciliation, referenceID)
        val foundJob    = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID)
        assert(foundJob.nonEmpty)

        val foundNextJob = jobManager.getNextAvailableJob(Reconciliation).get
        jobManager.completeJob(
          jobId,
          ReconciliationInfo(
            accountId                   = UUID.randomUUID().toString,
            accountName                 = "test",
            reconciliationsCreatedCount = 1,
            processingTimeSeconds       = 1
          )
        )
        assert(jobManager.getNextAvailableJob(Reconciliation).isEmpty)

  test("submit and create error job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID  = UUID.randomUUID()
        val jobId        = jobManager.submitJob(Reconciliation, referenceID)
        val foundNextJob = jobManager.getNextAvailableJob(Reconciliation).get
        jobManager.markJobInError(jobId, new IllegalStateError("boom"))
        assert(jobManager.getNextAvailableJob(Reconciliation).isEmpty)
        val foundErrorJob = jobRepo.findById(jobId).get
        assertEquals(foundErrorJob.errorMessage.get, "boom")

  test("submitAndProcessJob succeeds"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val errorMsg    = "processing failed"
        jobManager.submitAndProcessJob(Reconciliation, referenceID): job =>
          ReconciliationInfo(
            accountId                   = referenceID.toString,
            accountName                 = "test",
            reconciliationsCreatedCount = 1,
            processingTimeSeconds       = 1
          )

        val foundJob = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).head
        assertEquals(foundJob.jobStatus, perfectpair.domain.model.JobStatus.Complete)

  test("submitAndProcessJob marks job in error when processFn throws"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val errorMsg    = "processing failed"
        jobManager.submitAndProcessJob(Reconciliation, referenceID): job =>
          throw new RuntimeException(errorMsg)
        val foundJob = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).head
        assertEquals(foundJob.jobStatus, perfectpair.domain.model.JobStatus.Error)
        assertEquals(foundJob.errorMessage.get, errorMsg)

  test("complete with the wrong Job Info type"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID  = UUID.randomUUID()
        val jobId        = jobManager.submitJob(JobType.Ingestion, referenceID)
        val foundNextJob = jobManager.getNextAvailableJob(JobType.Ingestion).get

        intercept[IllegalArgumentError]:
          jobManager.completeJob(
            jobId,
            ReconciliationInfo(
              accountId                   = UUID.randomUUID().toString,
              accountName                 = "test",
              reconciliationsCreatedCount = 1,
              processingTimeSeconds       = 1
            )
          )

  test("pollForJob calls fn with submitted job and marks it InProgress"):
    // pollForJob creates its own transactions so we commit the job without rollBackOnly
    val referenceID = UUID.randomUUID()
    transact(transactor):
      jobManager.submitJob(Reconciliation, referenceID)

    var processedCount = 0

    jobManager.pollForJob(Reconciliation, 1): job =>
      if job.referenceId == referenceID then processedCount += 1

    assertEquals(processedCount, 1)

    val foundJob = transact(transactor):
      jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).headOption

    assertEquals(foundJob.map(_.jobStatus), Some(InProgress))

    // cleanup committed test data
    transact(transactor):
      foundJob.foreach(j => jobRepo.deleteById(j.id))
