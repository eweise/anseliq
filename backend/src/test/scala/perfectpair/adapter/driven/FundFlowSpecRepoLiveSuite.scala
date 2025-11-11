package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.{DbDataSource, FundFlowConfigRepoLive}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.DataSetup
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.service.FundFlowCache
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class FundFlowSpecRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("Create Fund Flow Config"):
    transact(transactor):
      rollBackOnly:
        val fundFlowRepo = FundFlowConfigRepoLive()
        val data         = DataSetup()
        val cache        = FundFlowCache(fundFlowRepo)
        cache.init()
        val foundFundFlow = cache.getConfigByName(data.fundFlowConfig.name).get.fundFlow

        assertNotEquals(foundFundFlow, null)
