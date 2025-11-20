package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.eweise.perfectpair.adapter.driven.{DbDataSource, MovementRepoLive, ReconciliationRepoLive}
import com.eweise.perfectpair.domain.model.helper.JSONBProps
import com.typesafe.config.ConfigFactory
import perfectpair.domain.DataSetup
import com.eweise.perfectpair.domain.model.Direction.{IN, OUT}
import com.eweise.perfectpair.domain.model.{Movement, Reconciliation, createID}
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class MovementRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("insertWithIdempotency"):
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data        = DataSetup()
        val referenceID = createID()
        val props = Map("id" -> "1")
        val movement = Movement(
          inAccountId = data.journalAccount.id,
          outAccountId = data.reconAccount.id,
          referenceId = referenceID,
          properties = JSONBProps(props),
          amount = 100
        )
        movementRepo.insert(movement)
        movementRepo.insert(movement)
        val found = movementRepo.findById(movement.id).get
        assertEquals(found.properties.props, props)

  test("findUnreconciledByAccount"):
    val movementRepo = MovementRepoLive()
    val reconRepo    = ReconciliationRepoLive()

    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation()
        reconRepo.insert(reconciliation)

        val outReconMovement = Movement(
          inAccountId = data.journalAccount.id,
          outAccountId = data.reconAccount.id,
          amount = 100,
          reconciliationId = Some(reconciliation.id)
        )

        data.movementRepo.insert(outReconMovement)

        val foundMovement = movementRepo.findUnReconciledByAccountAndDirection(data.reconAccount.id, OUT)
        assertEquals(foundMovement.head.id, outReconMovement.id)

        val notFound = movementRepo.findUnReconciledByAccountAndDirection(data.reconAccount.id, IN)
        assertEquals(notFound.size, 0)

        val inReconMovement = Movement(
          inAccountId = data.reconAccount.id,
          outAccountId = data.reportAccount.id,
          amount = 100,
          reconciliationId = Some(reconciliation.id)
        )

        data.movementRepo.insert(inReconMovement)

        assertEquals(movementRepo.findUnReconciledByAccountAndDirection(data.reconAccount.id, IN).size, 1)
        val foundByAccount = movementRepo.findUnReconciledByAccount(data.reconAccount.id)

        assertEquals(foundByAccount.size, 2)

  test("findByAccount returns all movements for an account"):
    val movementRepo = MovementRepoLive()

    transact(transactor):
      rollBackOnly:
        val data = DataSetup()

        val movement1 = Movement(
          inAccountId = data.journalAccount.id,
          outAccountId = data.reconAccount.id,
          amount = 100
        )
        val movement2 = Movement(
          inAccountId = data.reconAccount.id,
          outAccountId = data.reportAccount.id,
          amount = 200
        )
        val movement3 = Movement(
          inAccountId = data.journalAccount.id,
          outAccountId = data.reportAccount.id,
          amount = 300
        )

        movementRepo.insert(movement1)
        movementRepo.insert(movement2)
        movementRepo.insert(movement3)

        val reconMovements = movementRepo.findByAccount(data.reconAccount.id)
        assertEquals(reconMovements.size, 2)

        val journalMovements = movementRepo.findByAccount(data.journalAccount.id)
        assertEquals(journalMovements.size, 2)

        val reportMovements = movementRepo.findByAccount(data.reportAccount.id)
        assertEquals(reportMovements.size, 2)

  test("findByAccount returns empty for account with no movements"):
    val movementRepo = MovementRepoLive()

    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        val movements = movementRepo.findByAccount(data.reconAccount.id)
        assertEquals(movements.size, 0)
