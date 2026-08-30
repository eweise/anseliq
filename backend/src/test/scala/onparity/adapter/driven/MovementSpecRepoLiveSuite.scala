package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.common.model.{FileFeedPayload, MovementSpecPayload}
import onparity.domain.model.*
import onparity.domain.port.driven.*
import onparity.domain.service.FundFlowOperations
import onparity.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class MovementSpecRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("findByFundFlowId returns movement specs belonging to the fund flow"):
    val movementSpecRepo = MovementSpecRepoLive()
    val fileFeedRepo     = FileFeedRepoLive()
    val accountRepo      = AccountRepoLive()
    val fundFlowRepo     = FundFlowConfigRepoLive()
    transact(transactor):
      rollBackOnly:
        val fundFlow = FundFlowConfig(name = UUID.randomUUID().toString)
        fundFlowRepo.insert(fundFlow)

        val inAccount  = Account(name = "in", fundFlowId = fundFlow.id)
        val outAccount = Account(name = "out", fundFlowId = fundFlow.id)
        accountRepo.insert(inAccount)
        accountRepo.insert(outAccount)

        val feed = FileFeed()
        fileFeedRepo.insert(feed)

        val spec = MovementSpec(inAccountId = inAccount.id, outAccountId = outAccount.id, fileFeedId = feed.id)
        movementSpecRepo.insert(spec)

        val results = movementSpecRepo.findByFundFlowId(fundFlow.id)
        assertEquals(results.size, 1)

  test("findByFundFlowId does not return movement specs from other fund flows"):
    val movementSpecRepo = MovementSpecRepoLive()
    val fileFeedRepo     = FileFeedRepoLive()
    val accountRepo      = AccountRepoLive()
    val fundFlowRepo     = FundFlowConfigRepoLive()
    transact(transactor):
      rollBackOnly:
        val fundFlowA = FundFlowConfig(name = UUID.randomUUID().toString)
        val fundFlowB = FundFlowConfig(name = UUID.randomUUID().toString)
        fundFlowRepo.insert(fundFlowA)
        fundFlowRepo.insert(fundFlowB)

        val inA  = Account(name = "in-a", fundFlowId = fundFlowA.id)
        val outA = Account(name = "out-a", fundFlowId = fundFlowA.id)
        val inB  = Account(name = "in-b", fundFlowId = fundFlowB.id)
        val outB = Account(name = "out-b", fundFlowId = fundFlowB.id)
        accountRepo.insert(inA); accountRepo.insert(outA)
        accountRepo.insert(inB); accountRepo.insert(outB)

        val feedA = FileFeed()
        val feedB = FileFeed()
        fileFeedRepo.insert(feedA)
        fileFeedRepo.insert(feedB)

        movementSpecRepo.insert(MovementSpec(inAccountId = inA.id, outAccountId = outA.id, fileFeedId = feedA.id))
        movementSpecRepo.insert(MovementSpec(inAccountId = inB.id, outAccountId = outB.id, fileFeedId = feedB.id))

        val results = movementSpecRepo.findByFundFlowId(fundFlowA.id)
        assertEquals(results.size, 1)

  test("findByFileFeedId returns every movement spec routed off a feed"):
    val movementSpecRepo = MovementSpecRepoLive()
    val fileFeedRepo     = FileFeedRepoLive()
    val accountRepo      = AccountRepoLive()
    val fundFlowRepo     = FundFlowConfigRepoLive()
    transact(transactor):
      rollBackOnly:
        val fundFlow = FundFlowConfig(name = UUID.randomUUID().toString)
        fundFlowRepo.insert(fundFlow)

        val accountA = Account(name = "a", fundFlowId = fundFlow.id)
        val accountB = Account(name = "b", fundFlowId = fundFlow.id)
        val recon    = Account(name = "recon", fundFlowId = fundFlow.id)
        accountRepo.insert(accountA)
        accountRepo.insert(accountB)
        accountRepo.insert(recon)

        val sharedFeed = FileFeed()
        val unusedFeed = FileFeed()
        fileFeedRepo.insert(sharedFeed)
        fileFeedRepo.insert(unusedFeed)

        movementSpecRepo.insert(
          MovementSpec(inAccountId = recon.id, outAccountId = accountA.id, fileFeedId = sharedFeed.id)
        )
        movementSpecRepo.insert(
          MovementSpec(inAccountId = recon.id, outAccountId = accountB.id, fileFeedId = sharedFeed.id)
        )

        assertEquals(movementSpecRepo.findByFileFeedId(sharedFeed.id).size, 2)
        assertEquals(movementSpecRepo.findByFileFeedId(unusedFeed.id).size, 0)

  test("FileFeed expectedCadenceHours round-trips through FundFlowOperations create/update and the DB"):
    transact(transactor):
      rollBackOnly:
        given movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
        given fileFeedRepo:       FileFeedRepo           = FileFeedRepoLive()
        given accountRepo:        AccountRepo            = AccountRepoLive()
        given fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
        given movementRepo:       MovementRepo           = MovementRepoLive()
        given reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
        val fundFlowOperations = FundFlowOperations()

        val created = fundFlowOperations.createFileFeed(FileFeedPayload(expectedCadenceHours = Some(24)))
        assertEquals(created.expectedCadenceHours, Some(24))
        assertEquals(fileFeedRepo.findById(UUID.fromString(created.id.get)).get.expectedCadenceHours, Some(24))

        val updated = fundFlowOperations.updateFileFeed(
          created.id.get,
          created.copy(expectedCadenceHours = None)
        )
        assertEquals(updated.expectedCadenceHours, None)
        assertEquals(fileFeedRepo.findById(UUID.fromString(created.id.get)).get.expectedCadenceHours, None)
