package com.eweise.perfectpair.driving

import com.eweise.perfectpair.adapter.driving.Payload
import com.eweise.perfectpair.domain.model.AccountType.Real
import com.eweise.perfectpair.domain.model.{Account, FundFlowConfig, FundFlowSpec, RealAccountSpec}

class PayloadSuite extends munit.FunSuite:

  test("Create Fund Flow Config"):
    val ff = FundFlowConfig(name = "name", fundFlowJson = upickle.write(FundFlowSpec()))
    val account = Account(name = "name", fundFlowId = ff.id, accountType = Real)
    Payload.realAccountSpecPayloadFromDomain(RealAccountSpec(name = "name1"))
    Payload.realAccountSpecPayloadFromDomain(RealAccountSpec(name = "name1"))
    Payload.fundFlowPayloadFromDomain(ff)

