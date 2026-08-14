package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.domain.DataSetup
import onparity.domain.model.{Job, JobStatus, JobType}
import onparity.domain.model.JobType.Ingestion
import onparity.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class JobRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("insertAndfindByID"):
    val jobRepo = JobRepoLive()
    transact(transactor):
      rollBackOnly:
        val data        = DataSetup()
        val referenceId = UUID.randomUUID()
        val job         = Job(jobType = Ingestion, referenceId = referenceId)

        jobRepo.insert(job)
        val foundJob = jobRepo.findByJobTypeAndReferenceId(Ingestion, referenceId)
        assert(foundJob.nonEmpty)
        val foundJobsByType = jobRepo.findByJobType(Ingestion)
        assertEquals(foundJobsByType.size, 1)

  test("progressJson round-trips through the database"):
    val jobRepo = JobRepoLive()
    transact(transactor):
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
