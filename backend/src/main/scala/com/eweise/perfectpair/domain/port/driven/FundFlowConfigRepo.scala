package com.eweise.perfectpair.domain.port.driven

import com.eweise.perfectpair.domain.model.FundFlowConfig

trait FundFlowConfigRepo extends DomainRepo[FundFlowConfig, FundFlowConfig]:
  def findByName(name :String):Txn[Option[FundFlowConfig]]
