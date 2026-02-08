package com.eweise.perfectpair.domain.service

import cats.data
import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.DbDataSource
import com.eweise.perfectpair.common.model.model.Zero
import com.eweise.perfectpair.domain.DataSetup
import com.eweise.perfectpair.domain.model.helper.JSONBProps
import com.eweise.perfectpair.domain.model.{Account, Movement, MovementSpec, createID, isReconciled}
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
          fundFlowCache      = data.fundFlowCache,
          accountRepo        = data.accountRepo,
          movementRepo       = data.movementRepo,
          movementSpecRepo   = data.movementSpecRepo,
          reconSpecRepo      = data.reconSpecRepo,
          reconRepo          = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )

        // create some movements
        createMovement(data, data.journalToRecon)
        createMovement(data, data.reportToRecon)

        reconciler.reconcileFundFlow(data.fundFlowConfig)

  def createMovement(data: DataSetup, movementSpec: MovementSpec): Txn[Unit] =
    data.movementRepo.insert(
      Movement(
        inAccountId    = movementSpec.inAccountId,
        outAccountId   = movementSpec.outAccountId,
        movementSpecId = movementSpec.id,
        amount         = 100,
        properties     = JSONBProps(Map("foo" -> "bar", "id" -> createID().toString))
      )
    )

  test("Post Amount balances and Reconcile"):
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        val ops = AccountOperations(
          fundFlowCache      = data.fundFlowCache,
          accountRepo        = data.accountRepo,
          movementRepo       = data.movementRepo,
          movementSpecRepo   = data.movementSpecRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )
        val reconciler = Reconciler(
          fundFlowCache      = data.fundFlowCache,
          accountRepo        = data.accountRepo,
          movementRepo       = data.movementRepo,
          movementSpecRepo   = data.movementSpecRepo,
          reconSpecRepo      = data.reconSpecRepo,
          reconRepo          = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )

        for _ <- 1 to 10
        do
          val keyID       = UUID.randomUUID().toString
          val referenceID = createID()
          ops.postAmount(data.journalToRecon, 1000, referenceID = referenceID, Map("id" -> keyID))
          ops.postAmount(data.reportToRecon, 1000, referenceID  = referenceID, Map("id" -> keyID))

