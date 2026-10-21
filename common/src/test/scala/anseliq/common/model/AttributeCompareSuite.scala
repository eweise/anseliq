package anseliq.common.model

import org.scalatest.funsuite.AnyFunSuite

class AttributeCompareSuite extends AnyFunSuite:

  test("DateAttribute compares the two sides' dates, not one side with itself") {
    val attr = DateAttribute(key = "date")
    assert(attr.compare(Map("date" -> "2026-10-01"), Map("date" -> "2026-10-02")) < 0)
    assert(attr.compare(Map("date" -> "2026-10-02"), Map("date" -> "2026-10-01")) > 0)
    assert(attr.compare(Map("date" -> "2026-10-01"), Map("date" -> "2026-10-01")) == 0)
  }

  test("StringAttribute compares the two sides' values") {
    val attr = StringAttribute(key = "id")
    assert(attr.compare(Map("id" -> "a"), Map("id" -> "b")) < 0)
    assert(attr.compare(Map("id" -> "a"), Map("id" -> "a")) == 0)
  }
