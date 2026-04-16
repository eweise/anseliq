package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model.MovementSpec

trait MovementSpecRepo extends DomainRepo[MovementSpec, MovementSpec]:
  def findByFundFlowId(fundFlowId: ID): Txn[Vector[MovementSpec]]
