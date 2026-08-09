package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.DbDataSource
import perfectpair.domain.DataSetup
import perfectpair.domain.model.*
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class LedgerSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  def assertBalanceInvariant(reconciled: BigDecimal, unreconciled: BigDecimal, balance: BigDecimal): Unit =
    assertEquals(reconciled + unreconciled, balance, "reconciledBalance + unreconciledBalance must equal balance")

  test("recordMovements updates account balances and keeps reconciled + unreconciled equal to balance"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(
            MovementRequest(amount = 100, referenceId = createID(), properties = Map()),
            MovementRequest(amount = -30, referenceId = createID(), properties = Map()),
            MovementRequest(amount = 50, referenceId = createID(), properties = Map())
          )
        )

        val reconAccount   = data.accountRepo.findById(data.reconAccount.id).get
        val journalAccount = data.accountRepo.findById(data.journalAccount.id).get

        assertEquals(reconAccount.balance, BigDecimal(120)) // +100 -30 +50
        assertEquals(journalAccount.balance, BigDecimal(-120))

        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)
        assertBalanceInvariant(journalAccount.reconciledBalance, journalAccount.unreconciledBalance, journalAccount.balance)

  test("markReconciled moves an amount from unreconciled to reconciled without changing balance"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = 100, referenceId = createID(), properties = Map()))
        )

        data.ledger.markReconciled(data.reconAccount.id, 60)

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(100), "markReconciled must not change balance")
        assertEquals(reconAccount.reconciledBalance, BigDecimal(60))
        assertEquals(reconAccount.unreconciledBalance, BigDecimal(40))
        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)

  test("markUnreconciled reverses a prior markReconciled by the same amount"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = 100, referenceId = createID(), properties = Map()))
        )

        data.ledger.markReconciled(data.reconAccount.id, 60)
        data.ledger.markUnreconciled(data.reconAccount.id, 60)

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(100))
        assertEquals(reconAccount.reconciledBalance, BigDecimal(0))
        assertEquals(reconAccount.unreconciledBalance, BigDecimal(100))
        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)

  test("reconcile links matched movements to a reconciliation and shifts balance from unreconciled to reconciled"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given

        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = 100, referenceId = createID(), properties = Map()))
        )
        val movement        = data.movementRepo.findAll.head
        val reconciliation  = Reconciliation(specId = data.reconSpec.id)
        data.reconRepo.insert(reconciliation)

        data.ledger.reconcile(Seq(movement), reconciliation.id, data.reconAccount.id)

        val updatedMovement = data.movementRepo.findById(movement.id).get
        assertEquals(updatedMovement.reconciliationId, Some(reconciliation.id))

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(100), "reconcile must not change balance")
        assertEquals(reconAccount.reconciledBalance, BigDecimal(100))
        assertEquals(reconAccount.unreconciledBalance, BigDecimal(0))
        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)

  test("unreconcile clears the reconciliation link and reverses the balance shift"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given

        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = 100, referenceId = createID(), properties = Map()))
        )
        val movement       = data.movementRepo.findAll.head
        val reconciliation = Reconciliation(specId = data.reconSpec.id)
        data.reconRepo.insert(reconciliation)
        data.ledger.reconcile(Seq(movement), reconciliation.id, data.reconAccount.id)

        val reconciledMovement = data.movementRepo.findById(movement.id).get
        data.ledger.unreconcile(Seq(reconciledMovement), data.reconAccount.id)

        val updatedMovement = data.movementRepo.findById(movement.id).get
        assertEquals(updatedMovement.reconciliationId, None)

        val reconAccount = data.accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccount.balance, BigDecimal(100))
        assertEquals(reconAccount.reconciledBalance, BigDecimal(0))
        assertEquals(reconAccount.unreconciledBalance, BigDecimal(100))
        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)

  test("recordMovements with a negative amount swaps the in/out accounts"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        data.ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = -40, referenceId = createID(), properties = Map()))
        )

        val reconAccount   = data.accountRepo.findById(data.reconAccount.id).get
        val journalAccount = data.accountRepo.findById(data.journalAccount.id).get

        assertEquals(reconAccount.balance, BigDecimal(-40))
        assertEquals(journalAccount.balance, BigDecimal(40))

        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)
        assertBalanceInvariant(journalAccount.reconciledBalance, journalAccount.unreconciledBalance, journalAccount.balance)
