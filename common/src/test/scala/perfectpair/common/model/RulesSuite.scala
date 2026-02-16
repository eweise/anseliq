package perfectpair.common.model

import org.scalatest.funsuite.AnyFunSuite

import java.util.UUID

class RulesSuite extends AnyFunSuite:
  test("validate rule sql"):
    val matchRule1 = AttributeMatchRule(UUID.randomUUID(), UUID.randomUUID(), StringAttribute("key1"), StringAttribute("key2"))
    val matchRule2 = AttributeMatchRule(
      UUID.randomUUID(),
      UUID.randomUUID(),
      DateAttribute("key3", DateAdd(Interval.Hour, 4)),
      DateAttribute("key4")
    )

    val andRule = AndRule(List(matchRule1, matchRule2))

    val ruleJson = upickle.write(andRule)
    println(ruleJson)
    val ruleSQL = andRule.sql

    val sdf = Interval.Hour.toString
    println(ruleSQL)
