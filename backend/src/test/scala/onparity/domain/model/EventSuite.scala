package onparity.domain.model

import java.util.UUID

class EventSuite extends munit.FunSuite:
  test("classname shows class of case class"):
    val eventType = MoneyMovementsIngested(
      movementSpecID       = UUID.randomUUID(),
      movementSpecName     = "test",
      fileName             = "test",
      recordCount          = 0,
      ingestionTimeSeconds = 0,
      lastProcessedLine    = None
    ).eventType
    assertEquals("MoneyMovementsIngested", eventType)
