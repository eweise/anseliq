package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.model.{EventState, EventStatus}
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class EventStateRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("insert and findById"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event = EventState(eventType = "TestEvent", payload = """{"key":"value"}""")
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
        val event1 = EventState(eventType = "TestEvent", payload = "{}")
        val event2 = EventState(eventType = "TestEvent", payload = "{}")
        repo.insert(event1)
        repo.insert(event2)
        val found = repo.getNextPendingEvent("TestEvent", maxRetries = 3)
        assert(found.isDefined)

  test("getNextPendingEvent returns None when no pending events exist"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val found = repo.getNextPendingEvent("TestEvent", maxRetries = 3)
        assert(found.isEmpty)

  test("getNextPendingEvent excludes InProgress events"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event = EventState(eventType = "TestEvent", payload = "{}", status = EventStatus.InProgress)
        repo.insert(event)
        val found = repo.getNextPendingEvent("TestEvent", maxRetries = 3)
        assert(found.isEmpty)

  test("getNextPendingEvent excludes events that have reached maxRetries"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val maxRetries = 3
        val event      = EventState(eventType = "TestEvent", payload = "{}", retryCount = maxRetries)
        repo.insert(event)
        val found = repo.getNextPendingEvent("TestEvent", maxRetries = maxRetries)
        assert(found.isEmpty)

  test("findByEventType returns only events matching the given type"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event1 = EventState(eventType = "MoneyMovementsIngested", payload = "{}")
        val event2 = EventState(eventType = "AccountReconciled", payload = "{}")
        repo.insert(event1)
        repo.insert(event2)
        val found = repo.findByEventType("MoneyMovementsIngested")
        assert(found.exists(_.id == event1.id))
        assert(!found.exists(_.id == event2.id))
        assert(found.forall(_.eventType == "MoneyMovementsIngested"))

  test("findByEventType returns empty vector when no events match"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val found = repo.findByEventType("NonExistentType")
        assert(found.isEmpty)
