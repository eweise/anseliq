package anseliq.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import anseliq.common.model.{AppliedRules, RuleMatch, StringAttribute, StringAttributeMatchRule}
import anseliq.domain.DataSetup
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.model.{Movement, MovementSpec, Reconciliation, ReconciliationStatus}
import anseliq.testing.rollBackOnly

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

  test("openTotalsByAccountID totals InProgress and Mismatched reconciliations from the account's side"):
    val reconRepo    = ReconciliationRepoLive()
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        def recon(status: ReconciliationStatus) =
          val r = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual, status = status)
          reconRepo.insert(r)
          r
        // journalToRecon pays into the recon account (+ from its side), reportToRecon pays out of it (−).
        def movement(spec: MovementSpec, amount: BigDecimal) =
          val m = Movement(
            inAccountId    = spec.inAccountId,
            outAccountId   = spec.outAccountId,
            movementSpecId = spec.id,
            amount         = amount,
            properties     = JSONBProps(Map())
          )
          movementRepo.insert(m)
          m

        val inProgress  = recon(ReconciliationStatus.InProgress)
        val mismatchedA = recon(ReconciliationStatus.Mismatched)
        val mismatchedB = recon(ReconciliationStatus.Mismatched)
        val finalRecon  = recon(ReconciliationStatus.Final)
        movementRepo.setReconciliationIds(
          Seq(
            movement(data.journalToRecon, 31).id -> inProgress.id,
            movement(data.reportToRecon, 30).id  -> mismatchedA.id,
            movement(data.journalToRecon, 5).id  -> mismatchedB.id,
            movement(data.reportToRecon, 11).id  -> mismatchedB.id,
            movement(data.journalToRecon, 10).id -> finalRecon.id,
            movement(data.reportToRecon, 10).id  -> finalRecon.id
          )
        )

        val totals = reconRepo.openTotalsByAccountID(data.reconAccount.id).map(t => t.status -> t).toMap
        assertEquals(totals.keySet, Set(ReconciliationStatus.InProgress, ReconciliationStatus.Mismatched))
        assertEquals(totals(ReconciliationStatus.InProgress).count, 1)
        assertEquals(totals(ReconciliationStatus.InProgress).balance, BigDecimal(31))
        assertEquals(totals(ReconciliationStatus.Mismatched).count, 2)
        assertEquals(totals(ReconciliationStatus.Mismatched).balance, BigDecimal(-36))

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

  test("countByStatusForAccountID counts the account's reconciliations per status"):
    val reconRepo = ReconciliationRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        def recon(status: ReconciliationStatus) =
          reconRepo.insert(Reconciliation(specId = Some(data.reconSpec.id), appliedRules = AppliedRules.Manual, status = status))
        recon(ReconciliationStatus.Mismatched)
        recon(ReconciliationStatus.Mismatched)
        recon(ReconciliationStatus.Final)

        assertEquals(
          reconRepo.countByStatusForAccountID(data.reconAccount.id),
          Map(ReconciliationStatus.Mismatched -> 2, ReconciliationStatus.Final -> 1)
        )
        assertEquals(reconRepo.countByStatusForAccountID(data.journalAccount.id), Map.empty)
