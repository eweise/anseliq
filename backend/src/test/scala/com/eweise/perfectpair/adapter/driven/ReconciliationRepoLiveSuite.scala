package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.{DbDataSource, ReconciliationRepoLive}
import com.eweise.perfectpair.common.model.EqualityMatcher
import com.eweise.perfectpair.domain.DataSetup
import com.eweise.perfectpair.domain.model.Reconciliation
import com.eweise.perfectpair.testing.rollBackOnly
import com.typesafe.config.ConfigFactory

import java.util.UUID
import javax.sql.DataSource

class ReconciliationRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("Create and Find Reconciliation"):
    transact(transactor):
      rollBackOnly:
        val data  = DataSetup()

        val matcher = data.reconSpecRepo.findById(data.reconSpec.id).get.matcher()
        assert(matcher.isInstanceOf[EqualityMatcher])

        val recon = Reconciliation(description = "hey", specId = data.reconSpec.id)
        data.reconRepo.insert(recon)

        val found = data.reconRepo.findById(recon.id)
        assert(found.isDefined)
        assertEquals(found.get.description, "hey")

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
        val data           = DataSetup()
        val reconciliation = Reconciliation(description = "original", specId = data.reconSpec.id)
        reconRepo.insert(reconciliation)

        val updated = reconciliation.copy(description = "updated")
        reconRepo.update(updated)

        val found = reconRepo.findById(reconciliation.id)
        assertEquals(found.get.description, "updated")

  test("delete removes reconciliation"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation(specId = data.reconSpec.id, description = "to delete")
        reconRepo.insert(reconciliation)

        reconRepo.delete(reconciliation)

        val found = reconRepo.findById(reconciliation.id)
        assert(found.isEmpty)

  test("findAll returns all reconciliations"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val data         = DataSetup()
        val initialCount = reconRepo.findAll.size

        reconRepo.insert(Reconciliation(specId = data.reconSpec.id, description = "first"))
        reconRepo.insert(Reconciliation(specId = data.reconSpec.id, description = "second"))

        val allReconciliations = reconRepo.findAll
        assertEquals(allReconciliations.size, initialCount + 2)
