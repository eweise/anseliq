package perfectpair.domain.model

import java.util.UUID

class EventSuite extends munit.FunSuite:
  test("classname shows class of case class"):
    val eventType = MoneyMovementsIngested(movementSpecID = UUID.randomUUID()).eventType
    assertEquals("MoneyMovementsIngested", eventType)
