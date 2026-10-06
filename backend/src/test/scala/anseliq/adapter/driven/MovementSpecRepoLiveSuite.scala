package anseliq.adapter.driven

import anseliq.common.model.FileFeedPayload
import anseliq.domain.model.*
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.util.UUID

class MovementSpecRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("findByFundFlowId returns movement specs belonging to the fund flow"):
    val fundFlowRepo     = app.fundFlowConfigRepo
    transact(app.transactor):
      rollBackOnly:
        val fundFlow = FundFlowConfig(name = UUID.randomUUID().toString)
        fundFlowRepo.insert(fundFlow)

        val inAccount  = Account(name = "in", fundFlowId = fundFlow.id, accountType = AccountType.Recon)
        val outAccount = Account(name = "out", fundFlowId = fundFlow.id, accountType = AccountType.Secondary)
        app.accountRepo.insert(inAccount)
        app.accountRepo.insert(outAccount)

        val feed = FileFeed()
        app.fileFeedRepo.insert(feed)

        val spec = MovementSpec(inAccountId = inAccount.id, outAccountId = outAccount.id, fileFeedId = feed.id)
        app.movementSpecRepo.insert(spec)

        val results = app.movementSpecRepo.findByFundFlowId(fundFlow.id)
        assertEquals(results.size, 1)

  test("findByFundFlowId does not return movement specs from other fund flows"):
    val fundFlowRepo     = app.fundFlowConfigRepo
    transact(app.transactor):
      rollBackOnly:
        val fundFlowA = FundFlowConfig(name = UUID.randomUUID().toString)
        val fundFlowB = FundFlowConfig(name = UUID.randomUUID().toString)
        fundFlowRepo.insert(fundFlowA)
        fundFlowRepo.insert(fundFlowB)

        val inA  = Account(name = "in-a", fundFlowId = fundFlowA.id, accountType = AccountType.Recon)
        val outA = Account(name = "out-a", fundFlowId = fundFlowA.id, accountType = AccountType.Secondary)
        val inB  = Account(name = "in-b", fundFlowId = fundFlowB.id, accountType = AccountType.Recon)
        val outB = Account(name = "out-b", fundFlowId = fundFlowB.id, accountType = AccountType.Secondary)
        app.accountRepo.insert(inA); app.accountRepo.insert(outA)
        app.accountRepo.insert(inB); app.accountRepo.insert(outB)

        val feedA = FileFeed()
        val feedB = FileFeed()
        app.fileFeedRepo.insert(feedA)
        app.fileFeedRepo.insert(feedB)

        app.movementSpecRepo.insert(MovementSpec(inAccountId = inA.id, outAccountId = outA.id, fileFeedId = feedA.id))
        app.movementSpecRepo.insert(MovementSpec(inAccountId = inB.id, outAccountId = outB.id, fileFeedId = feedB.id))

        val results = app.movementSpecRepo.findByFundFlowId(fundFlowA.id)
        assertEquals(results.size, 1)

  test("findByFileFeedId returns every movement spec routed off a feed"):
    val fundFlowRepo     = app.fundFlowConfigRepo
    transact(app.transactor):
      rollBackOnly:
        val fundFlow = FundFlowConfig(name = UUID.randomUUID().toString)
        fundFlowRepo.insert(fundFlow)

        val accountA = Account(name = "a", fundFlowId = fundFlow.id, accountType = AccountType.Secondary)
        val accountB = Account(name = "b", fundFlowId = fundFlow.id, accountType = AccountType.Secondary)
        val recon    = Account(name = "recon", fundFlowId = fundFlow.id, accountType = AccountType.Recon)
        app.accountRepo.insert(accountA)
        app.accountRepo.insert(accountB)
        app.accountRepo.insert(recon)

        val sharedFeed = FileFeed()
        val unusedFeed = FileFeed()
        app.fileFeedRepo.insert(sharedFeed)
        app.fileFeedRepo.insert(unusedFeed)

        app.movementSpecRepo.insert(
          MovementSpec(inAccountId = recon.id, outAccountId = accountA.id, fileFeedId = sharedFeed.id)
        )
        app.movementSpecRepo.insert(
          MovementSpec(inAccountId = recon.id, outAccountId = accountB.id, fileFeedId = sharedFeed.id)
        )

        assertEquals(app.movementSpecRepo.findByFileFeedId(sharedFeed.id).size, 2)
        assertEquals(app.movementSpecRepo.findByFileFeedId(unusedFeed.id).size, 0)

  test("FileFeed expectedCadenceHours round-trips through FileFeedService create/update and the DB"):
    transact(app.transactor):
      rollBackOnly:
        val created = app.fileFeedService.createFileFeed(FileFeedPayload(expectedCadenceHours = Some(24)))
        assertEquals(created.expectedCadenceHours, Some(24))
        assertEquals(app.fileFeedRepo.findById(UUID.fromString(created.id.get)).get.expectedCadenceHours, Some(24))

        val updated = app.fileFeedService.updateFileFeed(
          created.id.get,
          created.copy(expectedCadenceHours = None)
        )
        assertEquals(updated.expectedCadenceHours, None)
        assertEquals(app.fileFeedRepo.findById(UUID.fromString(created.id.get)).get.expectedCadenceHours, None)
