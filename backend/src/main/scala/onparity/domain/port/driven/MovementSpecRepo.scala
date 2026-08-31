package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.MovementSpec

trait MovementSpecRepo extends DomainRepo[MovementSpec, MovementSpec]:
  def findByFundFlowId(fundFlowId: ID): Txn[Vector[MovementSpec]]
  def findByFileFeedId(fileFeedId: ID): Txn[Vector[MovementSpec]]
