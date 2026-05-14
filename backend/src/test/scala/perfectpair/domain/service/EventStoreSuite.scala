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
