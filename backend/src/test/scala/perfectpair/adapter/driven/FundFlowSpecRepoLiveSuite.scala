package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.{DbDataSource, FundFlowConfigRepoLive}
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.service.FundFlowCache
import com.typesafe.config.ConfigFactory
import perfectpair.domain.DataSetup
import perfectpair.testing.rollBackOnly
import upickle.default.*

import java.util.UUID
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

  test("findById returns inserted fund flow config"):
    val fundFlowRepo = FundFlowConfigRepoLive()
    transact(transactor):
      rollBackOnly:
        val fundFlowSpec = FundFlowSpec(
          realAccountSpecs = Seq(RealAccountSpec(name = "test")),
          movementSpecs = Seq()
        )
        val config = FundFlowConfig(
          name = UUID.randomUUID().toString,
          fundFlowJson = write(fundFlowSpec)
        )
        fundFlowRepo.insert(config)

        val found = fundFlowRepo.findById(config.id)
        assert(found.isDefined)
        assertEquals(found.get.name, config.name)

  test("findById returns None for non-existent id"):
    val fundFlowRepo = FundFlowConfigRepoLive()
    transact(transactor):
      rollBackOnly:
        val found = fundFlowRepo.findById(UUID.randomUUID())
        assert(found.isEmpty)

  test("update modifies fund flow config"):
    val fundFlowRepo = FundFlowConfigRepoLive()
    transact(transactor):
      rollBackOnly:
        val fundFlowSpec = FundFlowSpec(
          realAccountSpecs = Seq(RealAccountSpec(name = "original")),
          movementSpecs = Seq()
        )
        val config = FundFlowConfig(
          name = "original-name",
          fundFlowJson = write(fundFlowSpec)
        )
        fundFlowRepo.insert(config)

        val updatedConfig = config.copy(name = "updated-name")
        fundFlowRepo.update(updatedConfig)

        val found = fundFlowRepo.findById(config.id)
        assertEquals(found.get.name, "updated-name")

  test("delete removes fund flow config"):
    val fundFlowRepo = FundFlowConfigRepoLive()
    transact(transactor):
      rollBackOnly:
        val fundFlowSpec = FundFlowSpec(
          realAccountSpecs = Seq(RealAccountSpec(name = "to-delete")),
          movementSpecs = Seq()
        )
        val config = FundFlowConfig(
          name = UUID.randomUUID().toString,
          fundFlowJson = write(fundFlowSpec)
        )
        fundFlowRepo.insert(config)

        fundFlowRepo.delete(config)

        val found = fundFlowRepo.findById(config.id)
        assert(found.isEmpty)

  test("findAll returns all fund flow configs"):
    val fundFlowRepo = FundFlowConfigRepoLive()
    transact(transactor):
      rollBackOnly:
        val initialCount = fundFlowRepo.findAll.size

        val fundFlowSpec = FundFlowSpec(
          realAccountSpecs = Seq(RealAccountSpec(name = "test")),
          movementSpecs = Seq()
        )

        fundFlowRepo.insert(FundFlowConfig(
          name = UUID.randomUUID().toString,
          fundFlowJson = write(fundFlowSpec)
        ))
        fundFlowRepo.insert(FundFlowConfig(
          name = UUID.randomUUID().toString,
          fundFlowJson = write(fundFlowSpec)
        ))

        val allConfigs = fundFlowRepo.findAll
        assertEquals(allConfigs.size, initialCount + 2)
