package anseliq.adapter.driven

import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.domain.service.LedgerCache
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.util.UUID

class LedgerRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("Create Ledger"):
    transact(app.transactor):
      rollBackOnly:
        val ledgerRepo = app.ledgerRepo
        val data       = DataSetup(app)
        val cache      = LedgerCache(ledgerRepo)
        cache.init()

  test("findById returns inserted ledger config"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val config = Ledger(
          name = UUID.randomUUID().toString
        )
        ledgerRepo.insert(config)

        val found = ledgerRepo.findById(config.id)
        assert(found.isDefined)
        assertEquals(found.get.name, config.name)

  test("findById returns None for non-existent id"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val found = ledgerRepo.findById(UUID.randomUUID())
        assert(found.isEmpty)

  test("update modifies ledger config"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val config = Ledger(
          name = "original-name"
        )
        ledgerRepo.insert(config)

        val updatedConfig = config.copy(name = "updated-name")
        ledgerRepo.update(updatedConfig)

        val found = ledgerRepo.findById(config.id)
        assertEquals(found.get.name, "updated-name")

  test("delete removes ledger config"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val config = Ledger(
          name = UUID.randomUUID().toString
        )
        ledgerRepo.insert(config)

        ledgerRepo.delete(config)

        val found = ledgerRepo.findById(config.id)
        assert(found.isEmpty)

  test("findAll returns all ledger configs"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val initialCount = ledgerRepo.findAll.size

        ledgerRepo.insert(
          Ledger(
            name = UUID.randomUUID().toString
          )
        )
        ledgerRepo.insert(
          Ledger(
            name = UUID.randomUUID().toString
          )
        )

        val allConfigs = ledgerRepo.findAll
        assertEquals(allConfigs.size, initialCount + 2)
