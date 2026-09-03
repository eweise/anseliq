package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.MovementSpecPayload
import onparity.domain.DataSetup
import onparity.domain.port.driven.*
import onparity.testing.rollBackOnly

import javax.sql.DataSource

class FundFlowOperationsSuite extends munit.FunSuite:
  given transactor:        Transactor             = Transactor(dataSource)
  given dataSource:        DataSource             = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given movementSpecRepo:  MovementSpecRepo       = MovementSpecRepoLive()
  given fileFeedRepo:      FileFeedRepo           = FileFeedRepoLive()
  given accountRepo:       AccountRepo            = AccountRepoLive()
  given fundFlowConfigRepo: FundFlowConfigRepo    = FundFlowConfigRepoLive()
  given movementRepo:      MovementRepo           = MovementRepoLive()
  given reconSpecRepo:     ReconciliationSpecRepo = ReconciliationSpecRepoLive()

  test("createMovementSpec succeeds linking a Primary account to a Recon account"):
    transact(transactor):
      rollBackOnly:
        val data       = DataSetup()
        val operations = FundFlowOperations()
        val spec = operations.createMovementSpec(
          MovementSpecPayload(
            name         = "report to recon",
            outAccountId = data.reconAccount.id.toString,
            inAccountId  = data.reportAccount.id.toString,
            fileFeedId   = data.reportToReconFeed.id.toString
          )
        )
        assert(spec.id.isDefined)

  test("createMovementSpec succeeds linking a Secondary account to a Recon account"):
    transact(transactor):
      rollBackOnly:
        val data       = DataSetup()
        val operations = FundFlowOperations()
        val spec = operations.createMovementSpec(
          MovementSpecPayload(
            name         = "journal to recon",
            outAccountId = data.journalAccount.id.toString,
            inAccountId  = data.reconAccount.id.toString,
            fileFeedId   = data.journalToReconFeed.id.toString
          )
        )
        assert(spec.id.isDefined)

  test("createMovementSpec rejects linking a Primary account directly to a Secondary account"):
    transact(transactor):
      rollBackOnly:
        val data       = DataSetup()
        val operations = FundFlowOperations()
        intercept[IllegalArgumentException]:
          operations.createMovementSpec(
            MovementSpecPayload(
              name         = "primary to secondary",
              outAccountId = data.journalAccount.id.toString,
              inAccountId  = data.reportAccount.id.toString,
              fileFeedId   = data.journalToReconFeed.id.toString
            )
          )

  test("createMovementSpec rejects linking two Recon accounts together"):
    transact(transactor):
      rollBackOnly:
        val data       = DataSetup()
        val operations = FundFlowOperations()
        val otherRecon = onparity.domain.model.Account(
          name        = "other-recon",
          fundFlowId  = data.fundFlowConfig.id,
          accountType = onparity.domain.model.AccountType.Recon
        )
        data.accountRepo.insert(otherRecon)
        intercept[IllegalArgumentException]:
          operations.createMovementSpec(
            MovementSpecPayload(
              name         = "recon to recon",
              outAccountId = data.reconAccount.id.toString,
              inAccountId  = otherRecon.id.toString,
              fileFeedId   = data.journalToReconFeed.id.toString
            )
          )
