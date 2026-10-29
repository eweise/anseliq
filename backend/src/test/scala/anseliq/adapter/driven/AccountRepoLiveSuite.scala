package anseliq.adapter.driven

import anseliq.domain.DataSetup
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class AccountRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("findByIdForUpdate succeeds"):
    transact(app.transactor):
      rollBackOnly:
        val data   = DataSetup(app)
        val result = data.accountRepo.findByIdForUpdate(data.reconAccount.id)
        assert(result.isDefined)

  test("accountType round-trips through the database"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val account = anseliq.domain.model.Account(
          name        = "standalone",
          ledgerId  = data.ledger.id,
          accountType = anseliq.domain.model.AccountType.Primary,
          properties  = data.reconAccount.properties
        )
        data.accountRepo.insert(account)
        val found = data.accountRepo.findById(account.id)
        assertEquals(found.map(_.accountType), Some(anseliq.domain.model.AccountType.Primary))
