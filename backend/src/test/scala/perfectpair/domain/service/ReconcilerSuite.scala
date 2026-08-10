package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, MovementRepoLive}
import perfectpair.domain.DataSetup
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{Movement, MovementSpec, createID}
import perfectpair.domain.port.driven.Txn
import perfectpair.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class ReconcilerSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  test("Post Amount balances".ignore): // broken because of relies on old sql implementation

    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()

        data.fundFlowCache.init()
        val reconciler = Reconciler(
          accountRepo        = data.accountRepo,
          movementRepo       = data.movementRepo,
          movementSpecRepo   = data.movementSpecRepo,
          reconSpecRepo      = data.reconSpecRepo,
          reconRepo          = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )

        // create some movements
        val idToMatchMovements = "123"
        val journalMovement    = createMovement(data, data.journalToRecon, idToMatchMovements)
        val reportMovement     = createMovement(data, data.reportToRecon, idToMatchMovements)

        val journalMBeforeReconciled = movementRepo.findById(journalMovement.id).get
        assertEquals(None, journalMBeforeReconciled.reconciliationId)

        reconciler.reconcileAccount(data.reconAccount)

        val journalAfterReconciled = movementRepo.findById(journalMovement.id).get
        assert(journalAfterReconciled.reconciliationId.nonEmpty)

  test("reconcileAccount matches movements sharing the same id and marks both sides reconciled"):
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        val reconciler = Reconciler(
          accountRepo        = data.accountRepo,
          movementRepo       = data.movementRepo,
          movementSpecRepo   = data.movementSpecRepo,
          reconSpecRepo      = data.reconSpecRepo,
          reconRepo          = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )

        val idToMatch       = "match-me"
        val journalMovement = createMovement(data, data.journalToRecon, idToMatch)
        val reportMovement  = createMovement(data, data.reportToRecon, idToMatch)

        reconciler.reconcileAccount(data.reconAccount)

        val journalAfter = movementRepo.findById(journalMovement.id).get
        val reportAfter  = movementRepo.findById(reportMovement.id).get
        assert(journalAfter.reconciliationId.nonEmpty, "journal movement should be reconciled")
        assert(reportAfter.reconciliationId.nonEmpty, "report movement should be reconciled")
        assertEquals(journalAfter.reconciliationId, reportAfter.reconciliationId)

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
        val ops = FundFlowOperations()
        val reconciler = Reconciler(
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
