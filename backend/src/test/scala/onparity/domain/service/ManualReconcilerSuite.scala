package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.{DbDataSource, EventRepoLive, JobRepoLive}
import onparity.domain.DataSetup
import onparity.domain.model.helper.JSONBProps
import onparity.domain.model.{AccountReconciled, IllegalArgumentError, IllegalStateError, Movement, MovementSpec, ReconciliationStatus}
import onparity.domain.port.driven.{JobRepo, Txn}
import onparity.testing.rollBackOnly

import javax.sql.DataSource

class ManualReconcilerSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given clockSvc:   ClockService = ClockServiceLive()
  given eventRepo:  EventRepoLive()
  given eventStore: EventStore = EventStore()
  given jobRepo:    JobRepo = JobRepoLive()

  def createMovement(movementSpec: MovementSpec, amount: BigDecimal)(using data: DataSetup): Txn[Movement] =
    val m = Movement(
      inAccountId    = movementSpec.inAccountId,
      outAccountId   = movementSpec.outAccountId,
      movementSpecId = movementSpec.id,
      amount         = amount,
      properties     = JSONBProps(Map())
    )
    data.movementRepo.insert(m)
    m

  test("matchMovements with equal totals creates a Final reconciliation and links both movements"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)

        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement.id),
          "test match"
        )

        assertEquals(recon.status, ReconciliationStatus.Final)
        assertEquals(recon.balance, BigDecimal(0))
        assert(recon.createdAt.isDefined, "createdAt must be set on the returned reconciliation, not just the persisted row")
        assert(recon.updatedAt.isDefined, "updatedAt must be set on the returned reconciliation, not just the persisted row")

        val updatedIn  = data.movementRepo.findById(inMovement.id).get
        val updatedOut = data.movementRepo.findById(outMovement.id).get
        assertEquals(updatedIn.reconciliationId, Some(recon.id))
        assertEquals(updatedOut.reconciliationId, Some(recon.id))

        val account = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(account.reconciledBalance, BigDecimal(0))     // +100 (in) -100 (out) = 0
        assertEquals(account.unreconciledBalance, BigDecimal(0))

  test("matchMovements with unequal totals creates an InProgress reconciliation with the residual balance"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement   = createMovement(data.journalToRecon, 300)
        val outMovement1 = createMovement(data.reportToRecon, 100)
        val outMovement2 = createMovement(data.reportToRecon, 100)

        val recon = manualReconciler.matchMovements(
          data.reconSpec.id,
          Seq(inMovement.id),
          Seq(outMovement1.id, outMovement2.id),
          "test partial match"
        )

        assertEquals(recon.status, ReconciliationStatus.InProgress)
        assertEquals(recon.balance, BigDecimal(100))

        val account = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(account.reconciledBalance, BigDecimal(100))   // +300 (in) -100 -100 (out) = 100
        assertEquals(account.unreconciledBalance, BigDecimal(-100))

  test("matchMovements rejects a movement that is already reconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)

        manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "first match")

        val anotherOut = createMovement(data.reportToRecon, 100)
        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(anotherOut.id), "second match")

  test("matchMovements rejects a movement from the wrong movement spec"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val wrongSpecMovement = createMovement(data.reportToRecon, 100) // belongs to outMovementSpecId, passed as "in"
        val outMovement       = createMovement(data.reportToRecon, 100)

        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(wrongSpecMovement.id), Seq(outMovement.id), "bad spec")

  test("matchMovements rejects an empty selection on either side"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement = createMovement(data.journalToRecon, 100)

        intercept[IllegalArgumentError]:
          manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(), "no out movements")

  test("unmatch reverses account balances and returns movements to unreconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 60)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "to undo")

        val beforeUnmatch = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(beforeUnmatch.reconciledBalance, BigDecimal(40)) // +100 -60

        manualReconciler.unmatch(recon.id)

        val voided = data.reconRepo.findById(recon.id).get
        assertEquals(voided.status, ReconciliationStatus.Voided)

        val updatedIn  = data.movementRepo.findById(inMovement.id).get
        val updatedOut = data.movementRepo.findById(outMovement.id).get
        assertEquals(updatedIn.reconciliationId, None)
        assertEquals(updatedOut.reconciliationId, None)

        val afterUnmatch = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(afterUnmatch.reconciledBalance, BigDecimal(0))
        assertEquals(afterUnmatch.unreconciledBalance, BigDecimal(0))

  test("unmatch on an already-voided reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "double undo")

        manualReconciler.unmatch(recon.id)
        intercept[IllegalStateError]:
          manualReconciler.unmatch(recon.id)

  test("writeOff closes an InProgress reconciliation with a reason and leaves account balances untouched"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 90)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "residual match")
        assertEquals(recon.status, ReconciliationStatus.InProgress)

        val beforeWriteOff = data.accountRepo.findById(data.reconAccount.id).get

        manualReconciler.writeOff(recon.id, "bank fee")

        val closed = data.reconRepo.findById(recon.id).get
        assertEquals(closed.status, ReconciliationStatus.Final)
        assertEquals(closed.adjustmentReason, Some("bank fee"))
        assertEquals(closed.balance, BigDecimal(10)) // residual preserved as the historical write-off amount

        val afterWriteOff = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(afterWriteOff.reconciledBalance, beforeWriteOff.reconciledBalance)
        assertEquals(afterWriteOff.unreconciledBalance, beforeWriteOff.unreconciledBalance)

  test("writeOff on a Final reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "clean match")
        assertEquals(recon.status, ReconciliationStatus.Final)

        intercept[IllegalStateError]:
          manualReconciler.writeOff(recon.id, "should not work")

  test("writeOff on a Voided reconciliation errors"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val manualReconciler = ManualReconciler()

        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 100)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "voided match")
        manualReconciler.unmatch(recon.id)

        intercept[IllegalStateError]:
          manualReconciler.writeOff(recon.id, "should not work")

  test("after writeOff, InMemoryReconciler no longer tries to top up the reconciliation"):
    // reconcileAccountResumable commits its own transaction internally (unlike the rest of this
    // suite's tests, which call ManualReconciler methods directly within a rollBackOnly wrapper), so
    // it can't see this test's setup unless that setup is committed for real. Same real-commit +
    // explicit-teardown pattern as ReconcileAccountResumableSuite.
    given data: DataSetup = transact(transactor)(DataSetup())
    import data.given
    val manualReconciler    = ManualReconciler()
    val automaticReconciler = InMemoryReconciler()
    try
      val recon = transact(transactor):
        val inMovement  = createMovement(data.journalToRecon, 100)
        val outMovement = createMovement(data.reportToRecon, 90)
        val recon = manualReconciler.matchMovements(data.reconSpec.id, Seq(inMovement.id), Seq(outMovement.id), "will write off")
        manualReconciler.writeOff(recon.id, "bank fee")

        // A fresh unmatched out-movement that would otherwise top up this reconciliation's residual
        createMovement(data.reportToRecon, 10)
        recon

      automaticReconciler.reconcileAccountResumable(data.reconAccount.id)

      val stillClosed = transact(transactor)(data.reconRepo.findById(recon.id).get)
      assertEquals(stillClosed.status, ReconciliationStatus.Final)
      assertEquals(stillClosed.balance, BigDecimal(10))
    finally
      transact(transactor):
        eventRepo
          .findByEventType("AccountReconciled")
          .filter(es => scala.util.Try(es.event[AccountReconciled].accountID == data.reconAccount.id).getOrElse(false))
          .foreach(es => eventRepo.deleteById(es.id))
        data.reconRepo.findByReconSpecID(data.reconSpec.id).foreach(r => data.reconRepo.deleteById(r.id))
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)
