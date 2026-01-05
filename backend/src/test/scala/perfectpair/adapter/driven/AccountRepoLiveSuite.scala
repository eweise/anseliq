package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.{AccountRepoLive, DbDataSource}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.{Account, AccountType}
import com.eweise.perfectpair.domain.port.driven.AccountRepo
import com.typesafe.config.ConfigFactory
import perfectpair.domain.DataSetup
import perfectpair.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class AccountRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor   = null
  var dataSource: DataSource   = null
  var accountRepo: AccountRepo = null
  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)
    accountRepo = AccountRepoLive()

  test("findWithNonZeroBalance"):
    val accountRepo = AccountRepoLive()

    transact(transactor):
      rollBackOnly:
        accountRepo.findNominalWithNonZeroBalance(UUID.randomUUID())

  test("findAll"):
    val accountRepo = AccountRepoLive()

    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        val found = accountRepo.findAll()
        assertEquals(3, found.length)

  test("findByFundFlowAndAccountName"):
    val accountRepo = AccountRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        val reconAccounts = accountRepo
          .findByFundFlowAndAccountName(data.fundFlowConfig.id, data.reconAccountName)
        assertEquals(reconAccounts.size, 1)

  test("auditable attributes are created on insert"):
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()

        val account = Account(
          name = UUID.randomUUID().toString,
          accountType = AccountType.Real,
          fundFlowId = data.fundFlowConfig.id
        )
        assertEquals(account.createdAt, None)
        assertEquals(account.updatedAt, None)
        accountRepo.insert(account)
        assertEquals(account.createdAt.isEmpty, false)
        assertEquals(account.updatedAt.isEmpty, false)
        assertEquals(account.createdAt, account.updatedAt)

        accountRepo.update(account)
        assert(account.updatedAt.get.isAfter(account.createdAt.get))

  test("findByFundFlow returns all accounts for a fund flow"):
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        val accounts = accountRepo.findByFundFlow(data.fundFlowConfig.id)
        assertEquals(accounts.size, 3)
        assert(accounts.exists(_.name == data.reconAccountName))
        assert(accounts.exists(_.name == data.journalAccountName))
        assert(accounts.exists(_.name == data.reportAccountName))

  test("findByFundFlow returns empty for non-existent fund flow"):
    transact(transactor):
      rollBackOnly:
        val accounts = accountRepo.findByFundFlow(java.util.UUID.randomUUID())
        assertEquals(accounts.size, 0)
