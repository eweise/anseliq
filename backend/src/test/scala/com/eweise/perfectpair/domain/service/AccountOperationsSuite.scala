package com.eweise.perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.{AccountRepoLive, DbDataSource, FundFlowConfigRepoLive, MovementRepoLive}
import com.eweise.perfectpair.domain.model.*
import com.eweise.perfectpair.domain.port.driven.{AccountRepo, FundFlowConfigRepo, MovementRepo}
import com.eweise.perfectpair.domain.service.{AccountOperations, FundFlowCache}
import com.eweise.perfectpair.testing
import com.typesafe.config.ConfigFactory
import testing.rollBackOnly
import upickle.default.*

import scala.collection.immutable.Seq

class AccountOperationsSuite extends munit.FunSuite:
  val dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  val transactor: Transactor         = Transactor(dataSource)
  val configRepo: FundFlowConfigRepo = FundFlowConfigRepoLive()
  val accountRepo: AccountRepo       = AccountRepoLive()
  val movementRepo: MovementRepo     = MovementRepoLive()
  val fundFlowCache: FundFlowCache   = FundFlowCache(configRepo)

  val service = AccountOperations(
    fundFlowCache = fundFlowCache,
    accountRepo = accountRepo,
    movementRepo = movementRepo,
    configRepo = configRepo
  )


  test("Post Amount balances"):
    ()
//    transact(transactor):
//      testing.rollBackOnly:
//
//        service.createConfig(fundFlowConfig)
//
//        fundFlowCache.init()
//        val properties = Map("key" -> "123")
//        service.postAmount(fundFlowConfig.name, f.sendClearing.name, 100, properties)
//        val accounts = service.findMismatches(fundFlowConfig.name)
//
//        // make sure the none clearing accounts balance
//        val sumNonZeroAccounts = accounts.foldLeft(BigDecimal(0))((bal, account) => bal + account.balance)
//        assertEquals(BigDecimal(0), sumNonZeroAccounts)
//
//        service.postAmount(fundFlowConfig.name, "receiveBAIFile", 100)
//        val clearingAccount = accountRepo.findByFundFlowAndAccountName(fundFlowConfig.id, f.clearingReconciliation.name)
//
//        assertNotEquals(clearingAccount, None)
//
//        assertEquals(
//          BigDecimal(0),
//          clearingAccount.get.balance
//        )

    test("Post Amount does not balance by Join Key but account types balance"):
        ()
//    val fundFlowConfig = FundFlowConfig(name = createID().toString, fundFlowJson = write(f.cardFlow))
//    transact(transactor):
//      testing.rollBackOnly:
//
//        service.createConfig(fundFlowConfig)
//
//        fundFlowCache.init()
//        val joinKey1 = "123"
//        val joinKey2 = "987"
//        service.postAmount(fundFlowConfig.name, f.sendClearing.name, 100, joinKey1)
//        val accounts = service.getMismatches(fundFlowConfig.name)
//
//        // make sure it balances
//        val sumAccounts = accounts.foldLeft(BigDecimal(0))((b, a) => b + (a.balance))
//        Assert.assertEquals(BigDecimal(0), sumAccounts)
//
//        // Post the next leg with a different joinKey
//        service.postAmount(fundFlowConfig.name, f.receiveBAIFile.name, 100, joinKey2)
//        val baiFileAccounts = accountRepo.findByFundFlowAndJoinKey(fundFlowConfig.id, joinKey2)
//
//        // should have two accounts
//        assertEquals(2, baiFileAccounts.size)
//
//        Assert.assertEquals(
//          BigDecimal(100),
//          baiFileAccounts.filter(_.accountType == f.clearingReconciliation.name).head.balance
//        )
//
//        Assert.assertEquals(None, baiFileAccounts.find(_.accountType == f.clearingAccount.name))
