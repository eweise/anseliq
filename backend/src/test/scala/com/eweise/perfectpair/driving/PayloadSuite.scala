package com.eweise.perfectpair.driving

import com.eweise.perfectpair.adapter.driving.Payload
import com.eweise.perfectpair.domain.model.{Account, FundFlowConfig, FundFlowSpec, RealAccountSpec}

class PayloadSuite extends munit.FunSuite:

  test("Create Fund Flow Config"):
    val ff = FundFlowConfig(name = "name", fundFlowJson = """{"name":"4","realAccountSpecs":[{"name":"source1"},{"name":"source2"}]}""")
    val account = Account(name = "name", fundFlowId = ff.id)
    Payload.realAccountSpecPayloadFromDomain(RealAccountSpec(name = "name1"))
    Payload.realAccountSpecPayloadFromDomain(RealAccountSpec(name = "name1"))
    Payload.fundFlowPayloadFromDomain(ff)

