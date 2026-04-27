package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, JobRepoLive}
import perfectpair.domain.model.IllegalStateError
import perfectpair.domain.model.JobType.Reconciliation
import perfectpair.testing

import java.util.UUID

class JobManagerSuite extends munit.FunSuite:
  val dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  val transactor: Transactor = Transactor(dataSource)
  val jobRepo    = JobRepoLive()
  val jobManager = JobManager(jobRepo)

  test("submit and complete job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        jobManager.submitJob(Reconciliation, referenceID)
        val foundJob = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID)
        assert(foundJob.nonEmpty)

        val foundNextJob = jobManager.getNextAvailableJob(Reconciliation).get
        jobManager.completeJob(foundNextJob.jobType, foundNextJob.referenceId)
        assert(jobManager.getNextAvailableJob(Reconciliation).isEmpty)

  test("submit and complete job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        jobManager.submitJob(Reconciliation, referenceID)
        val foundNextJob = jobManager.getNextAvailableJob(Reconciliation).get
        jobManager.markJobInError(foundNextJob.jobType, foundNextJob.referenceId, new IllegalStateError("boom"))
        assert(jobManager.getNextAvailableJob(Reconciliation).isEmpty)
        val foundErrorJob = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).get
        assertEquals(foundErrorJob.errorMessage.get, "boom")
