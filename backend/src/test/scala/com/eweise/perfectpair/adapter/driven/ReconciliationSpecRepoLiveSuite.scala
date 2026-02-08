package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.domain.DataSetup
import com.eweise.perfectpair.testing.rollBackOnly
import com.typesafe.config.ConfigFactory

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
        val data = DataSetup()
        val foundReconSpecs = data.reconSpecRepo.findByFundFlow(data.fundFlowConfig.id)
        assert(foundReconSpecs.nonEmpty)
