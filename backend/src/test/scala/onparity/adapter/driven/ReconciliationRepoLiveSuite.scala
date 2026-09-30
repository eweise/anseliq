package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.common.model.AppliedRules
import onparity.domain.DataSetup
import onparity.domain.model.Reconciliation
import onparity.testing.rollBackOnly

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
        val data = DataSetup()

        val recon = Reconciliation(description = "hey", specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual)
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
        val reconciliation = Reconciliation(description = "original", specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual)
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
        val reconciliation = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual, description = "to delete")
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

        reconRepo.insert(Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual, description = "first"))
        reconRepo.insert(Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual, description = "second"))

        val allReconciliations = reconRepo.findAll
        assertEquals(allReconciliations.size, initialCount + 2)

  test("findByReconSpecID"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        reconRepo.insert(Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual, description = "first"))
        val foundRecons = reconRepo.findByReconSpecID(data.reconSpec.id)
        assert(foundRecons.nonEmpty)
