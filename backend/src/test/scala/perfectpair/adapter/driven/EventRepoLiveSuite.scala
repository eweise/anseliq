package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.model.{Event, EventStatus}
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class EventRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("insert and findById"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "TestEvent", payload = """{"key":"value"}""")
        repo.insert(event)
        val found = repo.findById(event.id)
        assert(found.isDefined)
        assertEquals(found.get.eventType, "TestEvent")
        assertEquals(found.get.status, EventStatus.Pending)
        assertEquals(found.get.retryCount, 0)

  test("getNextPendingEvent returns oldest pending event"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event1 = Event(eventType = "TestEvent", payload = "{}")
        val event2 = Event(eventType = "TestEvent", payload = "{}")
        repo.insert(event1)
        repo.insert(event2)
        val found = repo.getNextPendingEvent(maxRetries = 3)
        assert(found.isDefined)

  test("getNextPendingEvent returns None when no pending events exist"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val found = repo.getNextPendingEvent(maxRetries = 3)
        assert(found.isEmpty)

  test("getNextPendingEvent excludes InProgress events"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "TestEvent", payload = "{}", status = EventStatus.InProgress)
        repo.insert(event)
        val found = repo.getNextPendingEvent(maxRetries = 3)
        assert(found.isEmpty)

  test("getNextPendingEvent excludes events that have reached maxRetries"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val maxRetries = 3
        val event      = Event(eventType = "TestEvent", payload = "{}", retryCount = maxRetries)
        repo.insert(event)
        val found = repo.getNextPendingEvent(maxRetries = maxRetries)
        assert(found.isEmpty)
