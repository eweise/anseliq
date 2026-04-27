package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.DataSetup
import perfectpair.domain.model.Job
import perfectpair.domain.model.JobType.Ingestion
import perfectpair.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class JobRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("insertAndfindByReferenceID"):
    val jobRepo = JobRepoLive()
    transact(transactor):
      rollBackOnly:
        val data        = DataSetup()
        val referenceId = UUID.randomUUID()
        val job         = Job(jobType = Ingestion, referenceId = referenceId)

        jobRepo.insert(job)
        val foundJob = jobRepo.findByJobTypeAndReferenceId(Ingestion, referenceId)
        assert(foundJob.nonEmpty)
