package com.eweise.perfectpair.common.model

import org.scalatest.funsuite.AnyFunSuite

import java.util.UUID

class RulesSuite extends AnyFunSuite:
  test("validate rule sql"):
    val matchRule1 = AttributeMatchRule(UUID.randomUUID(), UUID.randomUUID(),"key1", "value1")

    val matchRule2 = AttributeMatchRule(UUID.randomUUID(), UUID.randomUUID(), "key2", "value1")

    val andRule = AndRule(List(matchRule1, matchRule2))


