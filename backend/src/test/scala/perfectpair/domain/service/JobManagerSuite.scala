package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, JobRepoLive}
import perfectpair.common.model.ReconciliationInfo
import perfectpair.domain.model.JobType.Reconciliation
import perfectpair.domain.model.{IllegalArgumentError, IllegalStateError, JobType}
import perfectpair.testing

import java.util.UUID

class JobManagerSuite extends munit.FunSuite:
  private val dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  private val transactor: Transactor = Transactor(dataSource)
  private val jobRepo    = JobRepoLive()
  private val jobManager = JobManager(jobRepo)

  test("submit and complete job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        jobManager.submitJob(Reconciliation, referenceID)
        val foundJob = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID)
        assert(foundJob.nonEmpty)

        val foundNextJob = jobManager.getNextAvailableJob(Reconciliation).get
        jobManager.completeJob(
          foundNextJob.jobType,
          foundNextJob.referenceId,
          ReconciliationInfo(reconciliationId = referenceID.toString)
        )
        assert(jobManager.getNextAvailableJob(Reconciliation).isEmpty)

  test("submit and create error job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        jobManager.submitJob(Reconciliation, referenceID)
        val foundNextJob = jobManager.getNextAvailableJob(Reconciliation).get
        jobManager.markJobInError(foundNextJob.jobType, foundNextJob.referenceId, new IllegalStateError("boom"))
        assert(jobManager.getNextAvailableJob(Reconciliation).isEmpty)
        val foundErrorJob = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).get
        assertEquals(foundErrorJob.errorMessage.get, "boom")

  test("complete with the wrong Job Info type"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        jobManager.submitJob(JobType.Ingestion, referenceID)
        val foundNextJob = jobManager.getNextAvailableJob(JobType.Ingestion).get

        intercept[IllegalArgumentError]:
          jobManager.completeJob(
            foundNextJob.jobType,
            foundNextJob.referenceId,
            ReconciliationInfo(reconciliationId = referenceID.toString)
          )
