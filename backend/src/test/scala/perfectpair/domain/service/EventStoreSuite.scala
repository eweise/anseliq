package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, EventRepoLive}
import perfectpair.domain.model.{Event, EventStatus, RetryableException}
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class EventStoreSuite extends munit.FunSuite:
  private val dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  private val transactor: Transactor = Transactor(dataSource)
  private val clock                  = ClockService()
  private val eventRepo              = EventRepoLive()
  private val eventStore             = EventStore(using eventRepo, clock, dataSource)

  test("publishEvent inserts event with Pending status"):
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "OrderPlaced", payload = """{"orderId":"123"}""")
        eventStore.publishEvent(event)
        val found = eventRepo.findById(event.id)
        assert(found.isDefined)
        assertEquals(found.get.status, EventStatus.Pending)
        assertEquals(found.get.retryCount, 0)

  test("processNextEvent calls consumer and marks event Consumed"):
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "OrderPlaced", payload = """{"orderId":"456"}""")
        eventStore.publishEvent(event)

        var consumed = false
        eventStore.processNextEvent(maxRetries = 3) { _ => consumed = true }

        assertEquals(consumed, true)
        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Consumed)

  test("processNextEvent does nothing when no pending events"):
    transact(transactor):
      rollBackOnly:
        var called = false
        eventStore.processNextEvent(maxRetries = 3) { _ => called = true }
        assertEquals(called, false)

  test("processNextEvent increments retryCount and keeps Pending on RetryableException"):
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "OrderPlaced", payload = "{}")
        eventStore.publishEvent(event)

        eventStore.processNextEvent(maxRetries = 3) { _ =>
          throw RetryableException("temporary failure")
        }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Pending)
        assertEquals(found.retryCount, 1)

  test("processNextEvent marks Error when retryCount reaches maxRetries on RetryableException"):
    transact(transactor):
      rollBackOnly:
        val maxRetries = 3
        val event      = Event(eventType = "OrderPlaced", payload = "{}", retryCount = maxRetries - 1)
        eventRepo.insert(event)

        eventStore.processNextEvent(maxRetries = maxRetries) { _ =>
          throw RetryableException("still failing")
        }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Error)
        assertEquals(found.errorMessage, Some("still failing"))

  test("processNextEvent marks Error on non-retryable exception"):
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "OrderPlaced", payload = "{}")
        eventStore.publishEvent(event)

        eventStore.processNextEvent(maxRetries = 3) { _ =>
          throw RuntimeException("fatal failure")
        }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Error)
        assertEquals(found.errorMessage, Some("fatal failure"))
