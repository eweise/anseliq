package anseliq.domain.service

import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.testing
import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.transact

class AccountServiceSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("Post Amount balances"):
    transact(app.transactor):
      testing.rollBackOnly:
        val dataSetup  = DataSetup(app)
        val properties = Map("key" -> "123")

        app.accountService.postAmount(
          movementSpec = dataSetup.journalToRecon,
          amount       = 100,
          referenceId  = createId(),
          properties   = properties
        )

        val ledgerAccounts = dataSetup.accountRepo.findByLedger(dataSetup.ledger.id)

        // make sure the non clearing accounts balance
        assertEquals(BigDecimal(0), ledgerAccounts.foldLeft(BigDecimal(0))((bal, account) => bal + account.balance))

        app.accountService.postAmount(
          movementSpec = dataSetup.reportToRecon,
          amount       = 100,
          referenceId  = createId(),
          properties   = properties
        )

        assertEquals(BigDecimal(0), ledgerAccounts.foldLeft(BigDecimal(0))((bal, account) => bal + account.balance))
