package anseliq.domain.service

import com.augustnagro.magnum.{DbTx, Transactor, transact}
import com.typesafe.config.ConfigFactory
import anseliq.adapter.driven.{DbDataSource, EventRepoLive}
import anseliq.domain.model.*
import anseliq.testing.rollBackOnly

import java.util.UUID
import javax.sql.DataSource

class EventStateStoreSuite extends munit.FunSuite:
  private val dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  private val transactor: Transactor = Transactor(dataSource)
  private val clock      = ClockServiceLive()
  private val eventRepo  = EventRepoLive()
  private val eventStore = EventStore(using eventRepo, clock, dataSource)

  // getNextPendingEvent picks the globally oldest Pending/Error row of a given event type (a real
  // job-queue query, not scoped to this test) — see EventRepoLive.getNextPendingEvent. On this
  // shared dev database, the app's background pollers leave real, never-drained Pending events
  // behind from ordinary manual/browser use, which would otherwise get picked up ahead of whatever
  // this test just inserted. Neutralizing them here only affects this test's rolled-back
  // transaction, so nothing outside the test is touched.
  private def neutralizePendingEventsOfType(eventType: String)(using DbTx): Unit =
    eventRepo
      .findByEventType(eventType)
      .filter(es => es.status == EventStatus.Pending || es.status == EventStatus.Error)
      .foreach(es => eventRepo.update(es.copy(status = EventStatus.Consumed)))

  test("publishEvent inserts event with Pending status"):
    transact(transactor):
      rollBackOnly:
        val event = MoneyMovementsIngested(
          movementSpecID       = UUID.randomUUID(),
          movementSpecName     = "test",
          fileName             = "test",
          recordCount          = 0,
          ingestionTimeSeconds = 0,
          lastProcessedLine    = None
        )
        eventStore.publishEvent(event)
        val found = eventRepo.findById(event.id)
        assert(found.isDefined)
        assertEquals(found.get.status, EventStatus.Pending)
        assertEquals(found.get.retryCount, 0)

  test("processNextEvent calls consumer and marks event Consumed"):
    transact(transactor):
      rollBackOnly:
        val event = MoneyMovementsIngested(
          movementSpecID       = UUID.randomUUID(),
          movementSpecName     = "test",
          fileName             = "test",
          recordCount          = 0,
          ingestionTimeSeconds = 0,
          lastProcessedLine    = None
        )
        eventStore.publishEvent(event)

        var consumed = false
        eventStore.processNextEvent(classOf[MoneyMovementsIngested], maxRetries = 3)(_ => consumed = true)

        assertEquals(consumed, true)
        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Consumed)

  test("processNextEvent does nothing when no pending events"):
    transact(transactor):
      rollBackOnly:
        neutralizePendingEventsOfType("AccountReconciled")
        var called = false
        eventStore.processNextEvent(classOf[AccountReconciled], maxRetries = 3)(_ => called = true)
        assertEquals(called, false)

  test("processNextEvent increments retryCount and keeps Pending if Exception"):
    transact(transactor):
      rollBackOnly:
        val event = MoneyMovementsIngested(
          movementSpecID       = UUID.randomUUID(),
          movementSpecName     = "test",
          fileName             = "test",
          recordCount          = 0,
          ingestionTimeSeconds = 0,
          lastProcessedLine    = None
        )
        eventStore.publishEvent(event)

        eventStore.processNextEvent(classOf[MoneyMovementsIngested], maxRetries = 3) { _ =>
          throw Exception("temporary failure")
        }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Pending)
        assertEquals(found.retryCount, 1)

  test("processNextEvent marks Error when retryCount reaches maxRetries on Exception "):
    transact(transactor):
      rollBackOnly:
        neutralizePendingEventsOfType("AccountReconciled")
        val maxRetries = 3
        val event      = AccountReconciled(accountID = UUID.randomUUID())
        eventRepo.insert(EventState.newEventState(event))

        for (i <- 1 to 3)
          eventStore.processNextEvent(classOf[AccountReconciled], maxRetries = maxRetries) { _ =>
            throw Exception("still failing")
          }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Error)
        assertEquals(found.errorMessage, Some("still failing"))

  test("processNextEvent marks Error on non-retryable exception"):
    transact(transactor):
      rollBackOnly:
        val event = MoneyMovementsIngested(
          movementSpecID       = UUID.randomUUID(),
          movementSpecName     = "test",
          fileName             = "test",
          recordCount          = 0,
          ingestionTimeSeconds = 0,
          lastProcessedLine    = None
        )
        eventStore.publishEvent(event)

        eventStore.processNextEvent(classOf[MoneyMovementsIngested], maxRetries = 3) { _ =>
          throw NonRetryableError("fatal failure")
        }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Error)
        assertEquals(found.errorMessage, Some("fatal failure"))
