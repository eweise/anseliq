package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.port.driven.{AccountRepo, FundFlowConfigRepo, MovementRepo, MovementSpecRepo, ReconciliationSpecRepo}
import onparity.domain.service.FundFlowOperations
import onparity.testing

class AccountOperationsSuite extends munit.FunSuite:
  val dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  val transactor: Transactor = Transactor(dataSource)

  given FundFlowConfigRepo     = FundFlowConfigRepoLive()
  given AccountRepo            = AccountRepoLive()
  given MovementRepo           = MovementRepoLive()
  given MovementSpecRepo       = MovementSpecRepoLive()
  given ReconciliationSpecRepo = ReconciliationSpecRepoLive()

  val fundFlowOperations = FundFlowOperations()

  test("Post Amount balances"):
    transact(transactor):
      testing.rollBackOnly:
        val dataSetup  = DataSetup()
        val properties = Map("key" -> "123")

        fundFlowOperations.postAmount(
          movementSpec = dataSetup.journalToRecon,
          amount       = 100,
          referenceID  = createID(),
          properties   = properties
        )

        val fundFlowAccounts = dataSetup.accountRepo.findByFundFlow(dataSetup.fundFlowConfig.id)

        // make sure the non clearing accounts balance
        assertEquals(BigDecimal(0), fundFlowAccounts.foldLeft(BigDecimal(0))((bal, account) => bal + account.balance))

        fundFlowOperations.postAmount(
          movementSpec = dataSetup.reportToRecon,
          amount       = 100,
          referenceID  = createID(),
          properties   = properties
        )

        assertEquals(BigDecimal(0), fundFlowAccounts.foldLeft(BigDecimal(0))((bal, account) => bal + account.balance))
