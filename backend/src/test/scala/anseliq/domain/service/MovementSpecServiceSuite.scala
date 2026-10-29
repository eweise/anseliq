package anseliq.domain.service

import anseliq.domain.DataSetup
import anseliq.domain.model.{Account, AccountType, IllegalArgumentError, MovementSpec}
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

// DataSetup's journal account is Primary and its report account Secondary, so money flows
// journal → recon → report.
class MovementSpecServiceSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private def link(data: DataSetup, from: Account, to: Account): MovementSpec =
    MovementSpec(
      name         = s"${from.name} to ${to.name}",
      outAccountId = from.id,
      inAccountId  = to.id,
      fileFeedId   = data.journalToReconFeed.id
    )

  private def assertRejected(from: DataSetup => Account, to: DataSetup => Account): Unit =
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val error =
          intercept[IllegalArgumentError](app.movementSpecService.createMovementSpec(link(data, from(data), to(data))))
        assert(error.msg.contains("Primary → Recon or Recon → Secondary"), error.msg)

  test("createMovementSpec succeeds linking a Primary account into a Recon account"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val spec = app.movementSpecService.createMovementSpec(link(data, data.journalAccount, data.reconAccount))
        assert(app.movementSpecRepo.findById(spec.id).isDefined)

  test("createMovementSpec succeeds linking a Recon account out to a Secondary account"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val spec = app.movementSpecService.createMovementSpec(link(data, data.reconAccount, data.reportAccount))
        assert(app.movementSpecRepo.findById(spec.id).isDefined)

  test("createMovementSpec rejects a Recon account paying a Primary account (reversed)"):
    assertRejected(_.reconAccount, _.journalAccount)

  test("createMovementSpec rejects a Secondary account paying a Recon account (reversed)"):
    assertRejected(_.reportAccount, _.reconAccount)

  test("createMovementSpec rejects linking a Primary account directly to a Secondary account"):
    assertRejected(_.journalAccount, _.reportAccount)

  test("createMovementSpec rejects linking two Recon accounts together"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        val otherRecon =
          Account(name = "other-recon", ledgerId = data.ledger.id, accountType = AccountType.Recon)
        data.accountRepo.insert(otherRecon)
        intercept[IllegalArgumentError]:
          app.movementSpecService.createMovementSpec(link(data, data.reconAccount, otherRecon))
