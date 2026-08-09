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
        val ledger = Ledger()

        ledger.recordMovements(
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

  test("recordMovements with a negative amount swaps the in/out accounts"):
    transact(transactor):
      rollBackOnly:
        given data: DataSetup = DataSetup()
        import data.given
        val ledger = Ledger()

        ledger.recordMovements(
          data.journalToRecon,
          Seq(MovementRequest(amount = -40, referenceId = createID(), properties = Map()))
        )

        val reconAccount   = data.accountRepo.findById(data.reconAccount.id).get
        val journalAccount = data.accountRepo.findById(data.journalAccount.id).get

        assertEquals(reconAccount.balance, BigDecimal(-40))
        assertEquals(journalAccount.balance, BigDecimal(40))

        assertBalanceInvariant(reconAccount.reconciledBalance, reconAccount.unreconciledBalance, reconAccount.balance)
        assertBalanceInvariant(journalAccount.reconciledBalance, journalAccount.unreconciledBalance, journalAccount.balance)
