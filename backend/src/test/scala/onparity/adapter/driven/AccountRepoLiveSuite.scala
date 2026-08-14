package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.domain.DataSetup
import onparity.testing.rollBackOnly

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

  test("isReconAccount returns true when account appears on both sides of a movement spec"):
    transact(transactor):
      rollBackOnly:
        val data   = DataSetup()
        val result = data.accountRepo.isReconAccount(data.reconAccount.id)
        assert(result)

  test("isReconAccount returns false when account only appears on one side of a movement spec"):
    transact(transactor):
      rollBackOnly:
        val data   = DataSetup()
        val result = data.accountRepo.isReconAccount(data.journalAccount.id)
        assert(!result)

  test("isReconAccount returns false when account has no movement specs"):
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        val account = onparity.domain.model.Account(
          name       = "standalone",
          fundFlowId = data.fundFlowConfig.id,
          properties = data.reconAccount.properties
        )
        data.accountRepo.insert(account)
        val result = data.accountRepo.isReconAccount(account.id)
        assert(!result)
