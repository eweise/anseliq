package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.domain.DataSetup
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.{AccountRepo, FundFlowConfigRepo, MovementRepo, MovementSpecRepo}
import perfectpair.testing

class AccountOperationsSuite extends munit.FunSuite:
  val dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  val transactor:       Transactor         = Transactor(dataSource)
  val configRepo:       FundFlowConfigRepo = FundFlowConfigRepoLive()
  val accountRepo:      AccountRepo        = AccountRepoLive()
  val movementRepo:     MovementRepo       = MovementRepoLive()
  val movementSpecRepo: MovementSpecRepo   = MovementSpecRepoLive()
  val accountOperations = AccountOperations(using 
    accountRepo        = accountRepo,
    movementRepo       = movementRepo,
    movementSpecRepo   = movementSpecRepo,
    fundFlowConfigRepo = configRepo
  )

  test("Post Amount balances"):
    transact(transactor):
      testing.rollBackOnly:
        val dataSetup  = DataSetup()
        val properties = Map("key" -> "123")

        accountOperations.postAmount(
          movementSpec = dataSetup.journalToRecon,
          amount       = 100,
          referenceID  = createID(),
          properties   = properties
        )

        val fundFlowAccounts = dataSetup.accountRepo.findByFundFlow(dataSetup.fundFlowConfig.id)

        // make sure the non clearing accounts balance
        assertEquals(BigDecimal(0), fundFlowAccounts.foldLeft(BigDecimal(0))((bal, account) => bal + account.balance))

        accountOperations.postAmount(
          movementSpec = dataSetup.reportToRecon,
          amount       = 100,
          referenceID  = createID(),
          properties   = properties
        )

        assertEquals(BigDecimal(0), fundFlowAccounts.foldLeft(BigDecimal(0))((bal, account) => bal + account.balance))
