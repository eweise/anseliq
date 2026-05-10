package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.DbDataSource
import perfectpair.domain.DataSetup
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{Movement, MovementSpec, createID}
import perfectpair.domain.port.driven.Txn
import perfectpair.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class InmemoryReconcilerSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  def createMovement(data: DataSetup, movementSpec: MovementSpec, idValue: String): Txn[Movement] =
    val m = Movement(
      inAccountId    = movementSpec.inAccountId,
      outAccountId   = movementSpec.outAccountId,
      movementSpecId = movementSpec.id,
      amount         = 100,
      properties     = JSONBProps(Map("foo" -> "bar", "id" -> idValue))
    )

    data.movementRepo.insert(m)
    m

  test("Post Amount balances and Reconcile"):
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        import data.given
        val ops = AccountOperations()

        given clock: ClockService = ClockService()
        val reconciler = InMemoryReconciler()

        for _ <- 1 to 3
        do
          val keyID = UUID.randomUUID().toString
          ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> keyID))
          ops.postAmount(data.reportToRecon, 1000, referenceID  = createID(), Map("id" -> keyID))

        reconciler.reconcileAccount(data.reconAccount.id)
        val noUnreconciled = data.movementRepo.findUnReconciledByAccount(data.reconAccount.id)
        assert(noUnreconciled.isEmpty)

        ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> createID().toString))
        ops.postAmount(data.journalToRecon, 1000, referenceID = createID(), Map("id" -> createID().toString))
        ops.postAmount(data.reportToRecon, 1000, referenceID  = createID(), Map("id" -> createID().toString))

        reconciler.reconcileAccount(data.reconAccount.id)
        val unreconciled = data.movementRepo.findUnReconciledByAccount(data.reconAccount.id)
        assertEquals(unreconciled.size, 3)
