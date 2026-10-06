package anseliq.domain.service

import anseliq.domain.DataSetup
import anseliq.domain.model.MovementSpec
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class MovementSpecServiceSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("createMovementSpec succeeds linking a Primary account to a Recon account"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val spec = app.movementSpecService.createMovementSpec(
          MovementSpec(
            name         = "report to recon",
            outAccountId = data.reconAccount.id,
            inAccountId  = data.reportAccount.id,
            fileFeedId   = data.reportToReconFeed.id
          )
        )
        assert(app.movementSpecRepo.findById(spec.id).isDefined)

  test("createMovementSpec succeeds linking a Secondary account to a Recon account"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val spec = app.movementSpecService.createMovementSpec(
          MovementSpec(
            name         = "journal to recon",
            outAccountId = data.journalAccount.id,
            inAccountId  = data.reconAccount.id,
            fileFeedId   = data.journalToReconFeed.id
          )
        )
        assert(app.movementSpecRepo.findById(spec.id).isDefined)

  test("createMovementSpec rejects linking a Primary account directly to a Secondary account"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        intercept[IllegalArgumentException]:
          app.movementSpecService.createMovementSpec(
            MovementSpec(
              name         = "primary to secondary",
              outAccountId = data.journalAccount.id,
              inAccountId  = data.reportAccount.id,
              fileFeedId   = data.journalToReconFeed.id
            )
          )

  test("createMovementSpec rejects linking two Recon accounts together"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val otherRecon = anseliq.domain.model.Account(
          name        = "other-recon",
          fundFlowId  = data.fundFlowConfig.id,
          accountType = anseliq.domain.model.AccountType.Recon
        )
        data.accountRepo.insert(otherRecon)
        intercept[IllegalArgumentException]:
          app.movementSpecService.createMovementSpec(
            MovementSpec(
              name         = "recon to recon",
              outAccountId = data.reconAccount.id,
              inAccountId  = otherRecon.id,
              fileFeedId   = data.journalToReconFeed.id
            )
          )
