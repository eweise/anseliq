package anseliq.adapter.driven

import anseliq.domain.model.*
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.util.UUID

class LedgerRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("findById returns an inserted ledger"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val ledger = Ledger(
          name = UUID.randomUUID().toString
        )
        ledgerRepo.insert(ledger)

        val found = ledgerRepo.findById(ledger.id)
        assert(found.isDefined)
        assertEquals(found.get.name, ledger.name)

  test("findById returns None for non-existent id"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val found = ledgerRepo.findById(UUID.randomUUID())
        assert(found.isEmpty)

  test("update modifies a ledger"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val ledger = Ledger(
          name = "original-name"
        )
        ledgerRepo.insert(ledger)

        val updated = ledger.copy(name = "updated-name")
        ledgerRepo.update(updated)

        val found = ledgerRepo.findById(ledger.id)
        assertEquals(found.get.name, "updated-name")

  test("delete removes a ledger"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val ledger = Ledger(
          name = UUID.randomUUID().toString
        )
        ledgerRepo.insert(ledger)

        ledgerRepo.delete(ledger)

        val found = ledgerRepo.findById(ledger.id)
        assert(found.isEmpty)

  test("findAll returns every ledger"):
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

        val allLedgers = ledgerRepo.findAll
        assertEquals(allLedgers.size, initialCount + 2)
