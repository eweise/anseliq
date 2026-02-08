package com.eweise.perfectpair.domain.port.driven

import com.eweise.perfectpair.common.model.model.ID
import com.eweise.perfectpair.domain.model.ReconciliationSpec

trait ReconciliationSpecRepo extends DomainRepo[ReconciliationSpec, ReconciliationSpec]:
  def findByFundFlow(fundFlowId: ID): Txn[Vector[ReconciliationSpec]]
