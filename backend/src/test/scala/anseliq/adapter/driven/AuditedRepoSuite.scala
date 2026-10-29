package anseliq.adapter.driven

import anseliq.domain.DataSetup
import anseliq.domain.model.{Movement, createID}
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.time.{Duration, OffsetDateTime}

// The app clock runs 72 hours ahead of the database's, so a timestamp the database filled in would
// be days away from clock.now rather than within a few seconds of it.
class AuditedRepoSuite extends munit.FunSuite:
  private val app = TestAppContainer()
  app.clock.addHours(72)

  private def assertNearClock(at: Option[OffsetDateTime])(using munit.Location): Unit =
    val now = app.clock.now
    assert(at.isDefined, "timestamp was not set")
    assert(Duration.between(at.get, now).abs.getSeconds < 60, s"${at.get} is not near the app clock's $now")

  test("insert stamps createdAt and updatedAt from the app clock"):
    transact(app.transactor):
      rollBackOnly:
        val data  = DataSetup(app)
        val found = app.accountRepo.findById(data.reconAccount.id).get
        assertNearClock(found.createdAt)
        assertNearClock(found.updatedAt)

  test("update moves updatedAt to the app clock and keeps createdAt"):
    transact(app.transactor):
      rollBackOnly:
        val data    = DataSetup(app)
        val created = app.ledgerRepo.findById(data.ledger.id).get
        app.clock.addHours(24)
        app.ledgerRepo.update(created.copy(name = "renamed"))
        val updated = app.ledgerRepo.findById(data.ledger.id).get
        assertEquals(updated.createdAt.map(_.toInstant), created.createdAt.map(_.toInstant))
        assertNearClock(updated.updatedAt)

  test("movement inserts, which use their own SQL, stamp from the app clock"):
    transact(app.transactor):
      rollBackOnly:
        val data = DataSetup(app)
        def newMovement() = Movement(
          inAccountId    = data.journalAccount.id,
          outAccountId   = data.reconAccount.id,
          movementSpecId = data.journalToRecon.id,
          referenceId    = createID(),
          amount         = 100
        )
        val single = newMovement()
        val batch  = newMovement()
        app.movementRepo.insert(single)
        app.movementRepo.insertAll(Seq(batch))
        Seq(single, batch).foreach: m =>
          val found = app.movementRepo.findById(m.id).get
          assertNearClock(found.createdAt)
          assertNearClock(found.updatedAt)
