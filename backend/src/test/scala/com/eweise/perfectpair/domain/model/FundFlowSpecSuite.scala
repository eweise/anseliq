package com.eweise.perfectpair.domain.model

class FundFlowSpecSuite extends munit.FunSuite:

  test("test pickling"):
    val spec = FundFlowSpec(realAccountSpecs = Seq(RealAccountSpec(name = "a1")), reconAccountSpecs = Seq(), movementSpecs = Seq())
    val jsonSpec = upickle.write(spec)
    val reHydrated = upickle.read[FundFlowSpec](jsonSpec)
    assertEquals(spec.realAccountSpecs.head.name, reHydrated.realAccountSpecs.head.name)
