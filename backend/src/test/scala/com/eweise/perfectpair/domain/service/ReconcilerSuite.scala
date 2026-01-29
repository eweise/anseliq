package com.eweise.perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.DbDataSource
import com.eweise.perfectpair.common.model.model.Zero
import com.eweise.perfectpair.domain.DataSetup
import com.eweise.perfectpair.domain.model.Direction.{IN, OUT}
import com.eweise.perfectpair.domain.model.helper.JSONBProps
import com.eweise.perfectpair.domain.model.{Account, Movement, isReconciled}
import com.eweise.perfectpair.domain.port.driven.Txn
import com.eweise.perfectpair.domain.service.{AccountOperations, Reconciler}
import com.typesafe.config.ConfigFactory
import com.eweise.perfectpair.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class ReconcilerSuite extends munit.FunSuite:
  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given transactor: Transactor = Transactor(dataSource)

  test("Post Amount balances"):
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()

        data.fundFlowCache.init()
        val reconciler = Reconciler(
          fundFlowCache = data.fundFlowCache,
          accountRepo = data.accountRepo,
          movementRepo = data.movementRepo,
          reconRepo = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )

        // create some movements
        createMovement(data, "123", data.reportAccount, data.reconAccount)
        createMovement(data, "123", data.reconAccount, data.journalAccount)

        val reconAccount = data.accountRepo
          .findByFundFlowAndAccountName(data.fundFlowConfig.id, data.reconAccountName)
          .get
        reconciler.reconcileAccount(reconAccount)

  def createMovement(data: DataSetup, id: String, inAccount: Account, outAccount: Account): Txn[Unit] =
    data.movementRepo.insert(
      Movement(
        inAccountId = inAccount.id,
        outAccountId = outAccount.id,
        amount = 100,
        properties = JSONBProps(Map("foo" -> "bar", "id" -> id))
      )
    )

  test("Post Amount balances and Reconcile"):
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        data.fundFlowCache.init()

        val reconciler = Reconciler(
          fundFlowCache = data.fundFlowCache,
          accountRepo = data.accountRepo,
          movementRepo = data.movementRepo,
          reconRepo = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )

        val ops = AccountOperations(
          fundFlowCache = data.fundFlowCache,
          accountRepo = data.accountRepo,
          movementRepo = data.movementRepo,
          configRepo = data.fundFlowConfigRepo
        )

        for _ <- 1 to 10
        do
          val keyID = UUID.randomUUID().toString
          ops.postAmount(data.fundFlowConfig.name, data.movementSpecPostJournal.name, 1000, Map("id" -> keyID))
          ops.postAmount(data.fundFlowConfig.name, data.movementSpecBankReport.name, 1000, Map("id" -> keyID))

        val reconAccount =
          data.accountRepo.findByFundFlowAndAccountName(data.fundFlowConfig.id, data.reconAccountSpec.name).get
        val inMovements  = data.movementRepo.findUnReconciledByAccountAndDirection(reconAccount.id, IN)
        val outMovements = data.movementRepo.findUnReconciledByAccountAndDirection(reconAccount.id, OUT)

        assertEquals(reconAccount.balance, Zero)
        assert(!inMovements.isReconciled)
        assert(!outMovements.isReconciled)

        // Now reconcile them
        reconciler.reconcileAccount(reconAccount)

        // Verify that they were reconciled
        val inReconciled  = data.movementRepo.findUnReconciledByAccountAndDirection(reconAccount.id, IN)
        val outReconciled = data.movementRepo.findUnReconciledByAccountAndDirection(reconAccount.id, OUT)

        assert(inReconciled.isReconciled)
        assert(outReconciled.isReconciled)

        assertEquals(reconAccount.balance, Zero)
