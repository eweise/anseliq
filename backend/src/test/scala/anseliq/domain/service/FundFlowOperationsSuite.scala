package anseliq.domain.service

import anseliq.common.model.MovementSpecPayload
import anseliq.domain.DataSetup
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class FundFlowOperationsSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("createMovementSpec succeeds linking a Primary account to a Recon account"):
    transact(app.transactor):
      rollBackOnly:
        val data       = DataSetup(app)
        val operations = app.fundFlowOperations
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
    transact(app.transactor):
      rollBackOnly:
        val data       = DataSetup(app)
        val operations = app.fundFlowOperations
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
    transact(app.transactor):
      rollBackOnly:
        val data       = DataSetup(app)
        val operations = app.fundFlowOperations
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
    transact(app.transactor):
      rollBackOnly:
        val data       = DataSetup(app)
        val operations = app.fundFlowOperations
        val otherRecon = anseliq.domain.model.Account(
          name        = "other-recon",
          fundFlowId  = data.fundFlowConfig.id,
          accountType = anseliq.domain.model.AccountType.Recon
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
