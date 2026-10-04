package anseliq.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import anseliq.domain.DataSetup
import anseliq.testing.rollBackOnly

import javax.sql.DataSource

class AccountRepoLiveSuite extends munit.FunSuite:

  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("findByIdForUpdate succeeds"):
    transact(transactor):
      rollBackOnly:
        val data   = DataSetup()
        val result = data.accountRepo.findByIdForUpdate(data.reconAccount.id)
        assert(result.isDefined)

  test("accountType round-trips through the database"):
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        val account = anseliq.domain.model.Account(
          name        = "standalone",
          fundFlowId  = data.fundFlowConfig.id,
          accountType = anseliq.domain.model.AccountType.Primary,
          properties  = data.reconAccount.properties
        )
        data.accountRepo.insert(account)
        val found = data.accountRepo.findById(account.id)
        assertEquals(found.map(_.accountType), Some(anseliq.domain.model.AccountType.Primary))
