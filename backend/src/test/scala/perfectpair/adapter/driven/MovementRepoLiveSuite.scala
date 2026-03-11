package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.common.model.model.ID
import perfectpair.common.model.{AndRule, AttributeMatchRule, StringAttribute}
import perfectpair.domain.DataSetup
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{Movement, Reconciliation, createID}
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
        val props       = Map("id" -> "1")
        val movement = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          referenceId    = referenceID,
          properties     = JSONBProps(props),
          amount         = 100
        )
        movementRepo.insert(movement)
        movementRepo.insert(movement)
        val found = movementRepo.findById(movement.id).get
        assertEquals(found.properties.props, props)

  test("insertAllWithIdempotency"):
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data        = DataSetup()
        val referenceID = createID()
        val props       = Map("id" -> "1")
        val movement = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          referenceId    = referenceID,
          properties     = JSONBProps(props),
          amount         = 100
        )
        val movement2 = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          referenceId    = referenceID,
          properties     = JSONBProps(props),
          amount         = 100
        )
        movementRepo.insertAll(List(movement, movement2))
        val found = movementRepo.findById(movement.id).get
        assertEquals(found.properties.props, props)

  test("findUnreconciledByAccount"):
    val movementRepo = MovementRepoLive()
    val reconRepo    = ReconciliationRepoLive()

    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation(specId = data.reconSpec.id, appliedRules = "")
        reconRepo.insert(reconciliation)

        val outReconMovement = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          amount         = 100
        )

        data.movementRepo.insert(outReconMovement)

        val foundMovement = movementRepo.findUnReconciledByAccount(data.reconAccount.id)
        assertEquals(foundMovement.head.id, outReconMovement.id)

        val inReconMovement = Movement(
          inAccountId      = data.reconAccount.id,
          outAccountId     = data.reportAccount.id,
          movementSpecId   = data.reportToRecon.id,
          amount           = 100,
          reconciliationId = Some(reconciliation.id)
        )

        data.movementRepo.insert(inReconMovement)

        assertEquals(movementRepo.findUnReconciledByAccount(data.reconAccount.id).size, 1)

//  test("generateLotsOfData"):
//    val movementRepo = MovementRepoLive()
//    val reconRepo    = ReconciliationRepoLive()
//
//    var mSpecID1 = ""
//    var mSpecID2 = ""
//
//    transact(transactor):
//      val data           = DataSetup()
//      val reconciliation = Reconciliation(specId = data.reconSpec.id)
//      reconRepo.insert(reconciliation)
//
//      for (i <- 1 to 5000)
//        val value = createID().toString
//        val outReconMovement = Movement(
//          inAccountId    = data.journalAccount.id,
//          outAccountId   = data.reconAccount.id,
//          movementSpecId = data.journalToRecon.id,
//          properties     = JSONBProps(Map("key1" -> value)),
//          amount         = 100
//        )
//
//        data.movementRepo.insert(outReconMovement)
//
//        val inReconMovement = Movement(
//          inAccountId      = data.reconAccount.id,
//          outAccountId     = data.reportAccount.id,
//          movementSpecId   = data.reportToRecon.id,
//          properties       = JSONBProps(Map("key2" -> value)),
//          amount           = 100,
//          reconciliationId = Some(reconciliation.id)
//        )
//        data.movementRepo.insert(inReconMovement)
//        mSpecID1 = data.journalToRecon.id.toString
//        mSpecID2 = data.reportToRecon.id.toString
//    print(mSpecID1)

  test("findMatches"):
    val movementRepo = MovementRepoLive()
    val reconRepo    = ReconciliationRepoLive()

    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation(specId = data.reconSpec.id, appliedRules = "")
        reconRepo.insert(reconciliation)

        val outReconMovement = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          properties     = JSONBProps(Map("key1" -> "value1")),
          amount         = 100
        )

        data.movementRepo.insert(outReconMovement)

        val inReconMovement = Movement(
          inAccountId      = data.reconAccount.id,
          outAccountId     = data.reportAccount.id,
          movementSpecId   = data.reportToRecon.id,
          properties       = JSONBProps(Map("key2" -> "value1")),
          amount           = 100,
          reconciliationId = Some(reconciliation.id)
        )

        data.movementRepo.insert(inReconMovement)

        val rule = AttributeMatchRule(
          inMovementSpecID  = data.reportToRecon.id,
          outMovementSpecID = data.journalToRecon.id,
          inKey             = StringAttribute(key = "key2"),
          outKey            = StringAttribute(key = "key1")
        )

        val sqlStr                        = rule.sql
        val results                       = movementRepo.findEqualityMatches(sqlStr)
        val (inMovementId, outMovementId) = results.head
        assertEquals(inReconMovement.id.toString, inMovementId)
        assertEquals(outReconMovement.id.toString, outMovementId)

        // Let's see if the AND rule works
        val sqlStrWithAnd                               = AndRule(List(rule, rule)).sql
        val resultsWithAnd                              = movementRepo.findEqualityMatches(sqlStrWithAnd)
        val (inMovementIdWithAnd, outMovementIdWithAnd) = resultsWithAnd.head
        assertEquals(inReconMovement.id.toString, inMovementIdWithAnd)
        assertEquals(outReconMovement.id.toString, outMovementIdWithAnd)

  test("findUnreconciledByAttribute"):
    val movementRepo = MovementRepoLive()
    val reconRepo    = ReconciliationRepoLive()

    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation(specId = data.reconSpec.id, appliedRules = "")
        reconRepo.insert(reconciliation)

        val outReconMovement = Movement(
          inAccountId      = data.journalAccount.id,
          outAccountId     = data.reconAccount.id,
          movementSpecId   = data.journalToRecon.id,
          amount           = 100,
          reconciliationId = Some(reconciliation.id),
          properties       = JSONBProps(Map("id" -> "1", "name" -> s"doug"))
        )
        data.movementRepo.insert(outReconMovement)

        val foundMovement = movementRepo.findUnreconciledByProperty("name", "doug")
        assertEquals(foundMovement.head.id, outReconMovement.id)

  test("findByAccount returns all movements for an account"):
    val movementRepo = MovementRepoLive()

    transact(transactor):
      rollBackOnly:
        val data = DataSetup()

        val movement1 = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          amount         = 100
        )
        val movement2 = Movement(
          inAccountId    = data.reconAccount.id,
          outAccountId   = data.reportAccount.id,
          movementSpecId = data.reportToRecon.id,
          amount         = 200
        )
        val movement3 = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reportAccount.id,
          movementSpecId = data.journalToRecon.id,
          amount         = 300
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
        val data      = DataSetup()
        val movements = movementRepo.findByAccount(data.reconAccount.id)
        assertEquals(movements.size, 0)
