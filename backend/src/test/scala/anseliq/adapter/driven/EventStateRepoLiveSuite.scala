package anseliq.adapter.driven

import anseliq.domain.model.EventState
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

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
