package anseliq.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import anseliq.adapter.driven.*
import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.domain.port.driven.{
  AccountRepo,
  FileFeedRepo,
  FundFlowConfigRepo,
  MovementRepo,
  MovementSpecRepo,
  ReconciliationSpecRepo
}
import anseliq.domain.service.FundFlowOperations
import anseliq.testing

class AccountOperationsSuite extends munit.FunSuite:
  val dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  val transactor: Transactor = Transactor(dataSource)

  given FundFlowConfigRepo     = FundFlowConfigRepoLive()
  given AccountRepo            = AccountRepoLive()
  given MovementRepo           = MovementRepoLive()
  given MovementSpecRepo       = MovementSpecRepoLive()
  given FileFeedRepo           = FileFeedRepoLive()
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
