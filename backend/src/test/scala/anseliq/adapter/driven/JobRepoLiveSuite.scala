package anseliq.adapter.driven

import anseliq.domain.DataSetup
import anseliq.domain.model.JobType.Ingestion
import anseliq.domain.model.{Job, JobStatus, JobType}
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.util.UUID

class JobRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("insertAndfindByID"):
    val jobRepo = app.jobRepo
    transact(app.transactor):
      rollBackOnly:
        val data        = DataSetup(app)
        val referenceId = UUID.randomUUID()
        val job         = Job(jobType = Ingestion, referenceId = referenceId)

        jobRepo.insert(job)
        val foundJob = jobRepo.findByJobTypeAndReferenceId(Ingestion, referenceId)
        assert(foundJob.nonEmpty)
        // findByJobType is a global, unscoped query (used in production to list every job of a
        // given type), so on this shared dev database it also returns real Ingestion jobs left
        // behind by the background job scheduler on every past `backend/run`. Assert membership,
        // not an absolute count.
        val foundJobsByType = jobRepo.findByJobType(Ingestion)
        assert(foundJobsByType.exists(_.id == job.id))

  test("progressJson round-trips through the database"):
    val jobRepo = app.jobRepo
    transact(app.transactor):
      rollBackOnly:
        val job = Job(
          referenceId = UUID.randomUUID(),
          jobType     = JobType.Reconciliation,
          jobStatus   = JobStatus.InProgress
        )
        jobRepo.insert(job)
        assertEquals(jobRepo.findById(job.id).get.progressJson, None)

        jobRepo.update(job.copy(progressJson = Some("""{"foo":"bar"}""")))
        assertEquals(jobRepo.findById(job.id).get.progressJson, Some("""{"foo":"bar"}"""))
