package anseliq.adapter.driven

import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.domain.service.FundFlowCache
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.util.UUID

class FundFlowSpecRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("Create Fund Flow Config"):
    transact(app.transactor):
      rollBackOnly:
        val fundFlowRepo = app.fundFlowConfigRepo
        val data         = DataSetup(app)
        val cache        = FundFlowCache(fundFlowRepo)
        cache.init()

  test("findById returns inserted fund flow config"):
    val fundFlowRepo = app.fundFlowConfigRepo
    transact(app.transactor):
      rollBackOnly:
        val config = FundFlowConfig(
          name = UUID.randomUUID().toString
        )
        fundFlowRepo.insert(config)

        val found = fundFlowRepo.findById(config.id)
        assert(found.isDefined)
        assertEquals(found.get.name, config.name)

  test("findById returns None for non-existent id"):
    val fundFlowRepo = app.fundFlowConfigRepo
    transact(app.transactor):
      rollBackOnly:
        val found = fundFlowRepo.findById(UUID.randomUUID())
        assert(found.isEmpty)

  test("update modifies fund flow config"):
    val fundFlowRepo = app.fundFlowConfigRepo
    transact(app.transactor):
      rollBackOnly:
        val config = FundFlowConfig(
          name = "original-name"
        )
        fundFlowRepo.insert(config)

        val updatedConfig = config.copy(name = "updated-name")
        fundFlowRepo.update(updatedConfig)

        val found = fundFlowRepo.findById(config.id)
        assertEquals(found.get.name, "updated-name")

  test("delete removes fund flow config"):
    val fundFlowRepo = app.fundFlowConfigRepo
    transact(app.transactor):
      rollBackOnly:
        val config = FundFlowConfig(
          name = UUID.randomUUID().toString
        )
        fundFlowRepo.insert(config)

        fundFlowRepo.delete(config)

        val found = fundFlowRepo.findById(config.id)
        assert(found.isEmpty)

  test("findAll returns all fund flow configs"):
    val fundFlowRepo = app.fundFlowConfigRepo
    transact(app.transactor):
      rollBackOnly:
        val initialCount = fundFlowRepo.findAll.size

        fundFlowRepo.insert(
          FundFlowConfig(
            name = UUID.randomUUID().toString
          )
        )
        fundFlowRepo.insert(
          FundFlowConfig(
            name = UUID.randomUUID().toString
          )
        )

        val allConfigs = fundFlowRepo.findAll
        assertEquals(allConfigs.size, initialCount + 2)
