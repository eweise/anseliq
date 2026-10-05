package anseliq.adapter.driven

import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.transact
import anseliq.domain.model.{EventState, EventStatus}
import anseliq.testing.rollBackOnly

class EventStateRepoLiveSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  test("insert and findById"):
    val repo = app.eventRepo
    transact(app.transactor):
      rollBackOnly:
        val event = EventState(eventType = "TestEvent", payload = """{"key":"value"}""")
        repo.insert(event)
        val found = repo.findById(event.id)
        assert(found.isDefined)
        assertEquals(found.get.eventType, "TestEvent")
        assertEquals(found.get.status, EventStatus.Pending)
        assertEquals(found.get.retryCount, 0)

  test("getNextPendingEvent returns oldest pending event"):
    val repo = app.eventRepo
    transact(app.transactor):
      rollBackOnly:
        val event1 = EventState(eventType = "TestEvent", payload = "{}")
        val event2 = EventState(eventType = "TestEvent", payload = "{}")
        repo.insert(event1)
        repo.insert(event2)
        val found = repo.getNextPendingEvent("TestEvent", maxRetries = 3)
        assert(found.isDefined)

  test("getNextPendingEvent returns None when no pending events exist"):
    val repo = app.eventRepo
    transact(app.transactor):
      rollBackOnly:
        val found = repo.getNextPendingEvent("TestEvent", maxRetries = 3)
        assert(found.isEmpty)

  test("getNextPendingEvent excludes InProgress events"):
    val repo = app.eventRepo
    transact(app.transactor):
      rollBackOnly:
        val event = EventState(eventType = "TestEvent", payload = "{}", status = EventStatus.InProgress)
        repo.insert(event)
        val found = repo.getNextPendingEvent("TestEvent", maxRetries = 3)
        assert(found.isEmpty)

  test("getNextPendingEvent excludes events that have reached maxRetries"):
    val repo = app.eventRepo
    transact(app.transactor):
      rollBackOnly:
        val maxRetries = 3
        val event      = EventState(eventType = "TestEvent", payload = "{}", retryCount = maxRetries)
        repo.insert(event)
        val found = repo.getNextPendingEvent("TestEvent", maxRetries = maxRetries)
        assert(found.isEmpty)

  test("findByEventType returns only events matching the given type"):
    val repo = app.eventRepo
    transact(app.transactor):
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
    val repo = app.eventRepo
    transact(app.transactor):
      rollBackOnly:
        val found = repo.findByEventType("NonExistentType")
        assert(found.isEmpty)
