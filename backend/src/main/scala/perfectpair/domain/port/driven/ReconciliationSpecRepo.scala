package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model.ReconciliationSpec

trait ReconciliationSpecRepo extends DomainRepo[ReconciliationSpec, ReconciliationSpec]:
  def findByAccountID(fundFlowId: ID): Txn[Vector[ReconciliationSpec]]
