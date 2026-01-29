package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.{DbDataSource, ReconciliationRepoLive}
import com.eweise.perfectpair.domain.DataSetup
import com.eweise.perfectpair.domain.model.Reconciliation
import com.typesafe.config.ConfigFactory
import com.eweise.perfectpair.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class ReconciliationRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("Create Reconciliation"):
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        data.reconRepo.insert(Reconciliation(description = "hey"))

  test("findById returns inserted reconciliation"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val reconciliation = Reconciliation(description = "test reconciliation")
        reconRepo.insert(reconciliation)

        val found = reconRepo.findById(reconciliation.id)
        assert(found.isDefined)
        assertEquals(found.get.description, "test reconciliation")

  test("findById returns None for non-existent id"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val found = reconRepo.findById(UUID.randomUUID())
        assert(found.isEmpty)

  test("update modifies reconciliation"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val reconciliation = Reconciliation(description = "original")
        reconRepo.insert(reconciliation)

        val updated = reconciliation.copy(description = "updated")
        reconRepo.update(updated)

        val found = reconRepo.findById(reconciliation.id)
        assertEquals(found.get.description, "updated")

  test("delete removes reconciliation"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val reconciliation = Reconciliation(description = "to delete")
        reconRepo.insert(reconciliation)

        reconRepo.delete(reconciliation)

        val found = reconRepo.findById(reconciliation.id)
        assert(found.isEmpty)

  test("findAll returns all reconciliations"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val initialCount = reconRepo.findAll.size

        reconRepo.insert(Reconciliation(description = "first"))
        reconRepo.insert(Reconciliation(description = "second"))

        val allReconciliations = reconRepo.findAll
        assertEquals(allReconciliations.size, initialCount + 2)
