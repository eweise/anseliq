package anseliq.adapter.driven

import anseliq.domain.model.*
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.util.UUID

class MovementSpecRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("findByLedgerId returns movement specs belonging to the ledger"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val ledger = Ledger(name = UUID.randomUUID().toString)
        ledgerRepo.insert(ledger)

        val inAccount  = Account(name = "in", ledgerId = ledger.id, accountType = AccountType.Recon)
        val outAccount = Account(name = "out", ledgerId = ledger.id, accountType = AccountType.Primary)
        app.accountRepo.insert(inAccount)
        app.accountRepo.insert(outAccount)

        val feed = FileFeed()
        app.fileFeedRepo.insert(feed)

        val spec = MovementSpec(inAccountId = inAccount.id, outAccountId = outAccount.id, fileFeedId = feed.id)
        app.movementSpecRepo.insert(spec)

        val results = app.movementSpecRepo.findByLedgerId(ledger.id)
        assertEquals(results.size, 1)

  test("findByLedgerId does not return movement specs from other ledgers"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val ledgerA = Ledger(name = UUID.randomUUID().toString)
        val ledgerB = Ledger(name = UUID.randomUUID().toString)
        ledgerRepo.insert(ledgerA)
        ledgerRepo.insert(ledgerB)

        val inA  = Account(name = "in-a", ledgerId = ledgerA.id, accountType = AccountType.Recon)
        val outA = Account(name = "out-a", ledgerId = ledgerA.id, accountType = AccountType.Primary)
        val inB  = Account(name = "in-b", ledgerId = ledgerB.id, accountType = AccountType.Recon)
        val outB = Account(name = "out-b", ledgerId = ledgerB.id, accountType = AccountType.Primary)
        app.accountRepo.insert(inA); app.accountRepo.insert(outA)
        app.accountRepo.insert(inB); app.accountRepo.insert(outB)

        val feedA = FileFeed()
        val feedB = FileFeed()
        app.fileFeedRepo.insert(feedA)
        app.fileFeedRepo.insert(feedB)

        app.movementSpecRepo.insert(MovementSpec(inAccountId = inA.id, outAccountId = outA.id, fileFeedId = feedA.id))
        app.movementSpecRepo.insert(MovementSpec(inAccountId = inB.id, outAccountId = outB.id, fileFeedId = feedB.id))

        val results = app.movementSpecRepo.findByLedgerId(ledgerA.id)
        assertEquals(results.size, 1)

  test("findByFileFeedId returns every movement spec routed off a feed"):
    val ledgerRepo = app.ledgerRepo
    transact(app.transactor):
      rollBackOnly:
        val ledger = Ledger(name = UUID.randomUUID().toString)
        ledgerRepo.insert(ledger)

        val accountA = Account(name = "a", ledgerId = ledger.id, accountType = AccountType.Primary)
        val accountB = Account(name = "b", ledgerId = ledger.id, accountType = AccountType.Primary)
        val recon    = Account(name = "recon", ledgerId = ledger.id, accountType = AccountType.Recon)
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
        val created = app.fileFeedService.createFileFeed(FileFeed(expectedCadenceHours = Some(24)))
        assertEquals(created.expectedCadenceHours, Some(24))
        assertEquals(app.fileFeedRepo.findById(created.id).get.expectedCadenceHours, Some(24))

        val updated = app.fileFeedService.updateFileFeed(
          created.id,
          name                 = created.name,
          dataSourceJson       = created.dataSourceJson,
          expectedCadenceHours = None
        )
        assertEquals(updated.expectedCadenceHours, None)
        assertEquals(app.fileFeedRepo.findById(created.id).get.expectedCadenceHours, None)
