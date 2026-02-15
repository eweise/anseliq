package com.eweise.perfectpair.common.model

import org.scalatest.funsuite.AnyFunSuite

import java.util.UUID

class RulesSuite extends AnyFunSuite:
  test("validate rule sql"):
    val matchRule1 = AttributeMatchRule(UUID.randomUUID(), UUID.randomUUID(),StringAttribute("key1"), StringAttribute("key2"))


    val andRule = AndRule(List(matchRule1, matchRule1))
    
    val ruleJson = upickle.write(andRule)
    println(ruleJson)


