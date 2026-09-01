package onparity.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.common.model.model.ID
import onparity.common.model.{AndRule, StringAttribute, StringAttributeMatchRule}
import onparity.domain.DataSetup
import onparity.domain.model.helper.JSONBProps
import onparity.domain.model.{Movement, Reconciliation, createID}
import onparity.testing.rollBackOnly

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
        val reconciliation = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = "")
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
        assertEquals(movementRepo.countUnreconciledByAccount(data.reconAccount.id), 1)

  test("findUnreconciledByMovementSpecID"):
    val movementRepo = MovementRepoLive()
    val reconRepo    = ReconciliationRepoLive()

    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = "")
        reconRepo.insert(reconciliation)

        val outReconMovement = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          amount         = 100
        )

        data.movementRepo.insert(outReconMovement)

        val foundOutMovements = movementRepo.findUnReconciledByMovementSpec(data.journalToRecon.id)
        assertEquals(foundOutMovements.head.id, outReconMovement.id)

        val inReconMovement = Movement(
          inAccountId      = data.reconAccount.id,
          outAccountId     = data.reportAccount.id,
          movementSpecId   = data.reportToRecon.id,
          amount           = 100,
          reconciliationId = Some(reconciliation.id)
        )

        data.movementRepo.insert(inReconMovement)

        val foundInMovements = movementRepo.findUnReconciledByAccount(data.reportToRecon.id)
        assertEquals(foundInMovements.size, 0)

  //  test("generateLotsOfData"):
//    val movementRepo = MovementRepoLive()
//    val reconRepo    = ReconciliationRepoLive()
//
//    var mSpecID1 = ""
//    var mSpecID2 = ""
//
//    transact(transactor):
//      val data           = DataSetup()
//      val reconciliation = Reconciliation(specId = Some(data.reconSpec.id))
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

  test("findMatches".ignore): // ignored because relies on old sql matching
    val movementRepo = MovementRepoLive()
    val reconRepo    = ReconciliationRepoLive()

    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = "")
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

        val rule = StringAttributeMatchRule(
          inKey  = StringAttribute(key = "key2"),
          outKey = StringAttribute(key = "key1")
        )

        val sqlStr                        = rule.sql
        val results                       = movementRepo.findEqualityMatches(sqlStr, data.reportToRecon.id, data.journalToRecon.id)
        val (inMovementId, outMovementId) = results.head
        assertEquals(inReconMovement.id.toString, inMovementId)
        assertEquals(outReconMovement.id.toString, outMovementId)

        // Let's see if the AND rule works
        val sqlStrWithAnd = AndRule(List(rule, rule)).sql
        val resultsWithAnd =
          movementRepo.findEqualityMatches(sqlStrWithAnd, data.reportToRecon.id, data.journalToRecon.id)
        val (inMovementIdWithAnd, outMovementIdWithAnd) = resultsWithAnd.head
        assertEquals(inReconMovement.id.toString, inMovementIdWithAnd)
        assertEquals(outReconMovement.id.toString, outMovementIdWithAnd)

  test("findUnreconciledByAttribute"):
    val movementRepo = MovementRepoLive()
    val reconRepo    = ReconciliationRepoLive()

    transact(transactor):
      rollBackOnly:
        val data           = DataSetup()
        val reconciliation = Reconciliation(specId = Some(data.reconSpec.id), appliedRules = "")
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

  test("findUnreconciledPage orders by the match key expression and pages via afterKey"):
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()

        for id <- Seq("30", "10", "20")
        do
          movementRepo.insert(
            Movement(
              inAccountId    = data.journalAccount.id,
              outAccountId   = data.reconAccount.id,
              movementSpecId = data.journalToRecon.id,
              amount         = 100,
              properties     = JSONBProps(Map("id" -> id))
            )
          )

        val matchKeyExpr = "a.properties['props'] ->> 'id'"

        val firstPage = movementRepo.findUnreconciledPage(
          movementSpecId = data.journalToRecon.id,
          matchKeyExpr   = matchKeyExpr,
          afterKey       = None,
          pageSize       = 2
        )
        assertEquals(firstPage.map(_.properties.props("id")), Vector("10", "20"))

        val secondPage = movementRepo.findUnreconciledPage(
          movementSpecId = data.journalToRecon.id,
          matchKeyExpr   = matchKeyExpr,
          afterKey       = Some(firstPage.last.properties.props("id")),
          pageSize       = 2
        )
        assertEquals(secondPage.map(_.properties.props("id")), Vector("30"))

  test("setReconciliationIds bulk-links each movement to its own reconciliation in one statement"):
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()

        val m1 = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          amount         = 100,
          properties     = JSONBProps(Map("id" -> "1"))
        )
        val m2 = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          amount         = 200,
          properties     = JSONBProps(Map("id" -> "2"))
        )
        val m3 = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          amount         = 300,
          properties     = JSONBProps(Map("id" -> "3"))
        )
        movementRepo.insert(m1)
        movementRepo.insert(m2)
        movementRepo.insert(m3)

        val reconRepo = ReconciliationRepoLive()
        val recon1    = Reconciliation(specId = Some(data.reconSpec.id))
        val recon2    = Reconciliation(specId = Some(data.reconSpec.id))
        reconRepo.insert(recon1)
        reconRepo.insert(recon2)

        // m1 and m3 both link to recon1, m2 links to a different reconciliation (recon2),
        // proving one statement can set different reconciliation_id values per row.
        movementRepo.setReconciliationIds(Seq(m1.id -> recon1.id, m2.id -> recon2.id, m3.id -> recon1.id))

        assertEquals(movementRepo.findById(m1.id).get.reconciliationId, Some(recon1.id))
        assertEquals(movementRepo.findById(m2.id).get.reconciliationId, Some(recon2.id))
        assertEquals(movementRepo.findById(m3.id).get.reconciliationId, Some(recon1.id))

        // Every other column must be untouched by the narrow update.
        assertEquals(movementRepo.findById(m2.id).get.amount, BigDecimal(200))
        assertEquals(movementRepo.findById(m2.id).get.properties.props("id"), "2")

  test("setReconciliationIds does nothing for an empty input"):
    val movementRepo = MovementRepoLive()
    transact(transactor):
      rollBackOnly:
        movementRepo.setReconciliationIds(Seq.empty)
