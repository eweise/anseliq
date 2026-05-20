package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.model.*
import perfectpair.testing.rollBackOnly

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

        val spec = MovementSpec(inAccountId = inAccount.id, outAccountId = outAccount.id)
        movementSpecRepo.insert(spec)

        val results = movementSpecRepo.findByFundFlowId(fundFlow.id)
        assertEquals(results.size, 1)

  test("findByFundFlowId does not return movement specs from other fund flows"):
    val movementSpecRepo = MovementSpecRepoLive()
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

        movementSpecRepo.insert(MovementSpec(inAccountId = inA.id, outAccountId = outA.id))
        movementSpecRepo.insert(MovementSpec(inAccountId = inB.id, outAccountId = outB.id))

        val results = movementSpecRepo.findByFundFlowId(fundFlowA.id)
        assertEquals(results.size, 1)
