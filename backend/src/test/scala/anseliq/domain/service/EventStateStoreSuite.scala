package anseliq.domain.service

import anseliq.domain.model.*
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

import java.util.UUID

class EventStateStoreSuite extends munit.FunSuite:
  private val app        = TestAppContainer()
  private val transactor = app.transactor
  private val eventRepo  = app.eventRepo
  private val eventStore = app.eventStore

  test("publishEvent records the event, readable back as the same event"):
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
        assertEquals(found.get.eventType, "MoneyMovementsIngested")
        assertEquals(found.get.event[Event], event)
