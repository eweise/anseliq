package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.common.model.{AppliedRules, RuleMatch, StringAttribute, StringAttributeMatchRule}
import onparity.domain.DataSetup
import onparity.domain.model.{Reconciliation, ReconciliationStatus}
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

  // Repo.update binds each column with the codec summoned where the Repo is defined, not via
  // Reconciliation's derived codec (which insert/select use), so a jsonb column can round-trip
  // through insert yet fail on update if its codec isn't in scope in ReconciliationRepoLive.
  test("update round-trips Matched appliedRules through the jsonb column"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual)
        reconRepo.insert(reconciliation)

        val rule    = StringAttributeMatchRule(inKey = StringAttribute(key = "id"), outKey = StringAttribute(key = "id"))
        val matched = AppliedRules.Matched(List(RuleMatch(rule, "42")))
        reconRepo.update(reconciliation.copy(appliedRules = matched))

        assertEquals(reconRepo.findById(reconciliation.id).get.appliedRules, matched)

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

  test("countInProgressByAccountID counts only the account's InProgress reconciliations"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        def recon(status: ReconciliationStatus) =
          Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual, status = status)
        reconRepo.insert(recon(ReconciliationStatus.InProgress))
        reconRepo.insert(recon(ReconciliationStatus.InProgress))
        reconRepo.insert(recon(ReconciliationStatus.Final))
        reconRepo.insert(recon(ReconciliationStatus.Voided))

        assertEquals(reconRepo.countInProgressByAccountID(data.reconAccount.id), 2)
        assertEquals(reconRepo.countInProgressByAccountID(data.journalAccount.id), 0)

  test("findByAccountID filters by status when one is given"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        def recon(status: ReconciliationStatus) =
          Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual, status = status)
        val inProgress = recon(ReconciliationStatus.InProgress)
        val finalRecon = recon(ReconciliationStatus.Final)
        reconRepo.insert(inProgress)
        reconRepo.insert(finalRecon)

        val accountId = data.reconAccount.id
        assertEquals(reconRepo.findByAccountID(accountId, 100).map(_.id).toSet, Set(inProgress.id, finalRecon.id))
        assertEquals(
          reconRepo.findByAccountID(accountId, 100, Some(ReconciliationStatus.InProgress)).map(_.id),
          Vector(inProgress.id)
        )
        assertEquals(
          reconRepo.findByAccountID(accountId, 100, Some(ReconciliationStatus.Final)).map(_.id),
          Vector(finalRecon.id)
        )
