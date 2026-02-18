package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.DataSetup
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class ReconciliationSpecRepoLiveSuite extends munit.FunSuite:

  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("Find by Fund Flow"):
    transact(transactor):
      rollBackOnly:
        val data            = DataSetup()
        val foundReconSpecs = data.reconSpecRepo.findByFundFlow(data.fundFlowConfig.id)
        assert(foundReconSpecs.nonEmpty)
