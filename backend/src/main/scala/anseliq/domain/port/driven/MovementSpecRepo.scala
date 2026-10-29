package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.MovementSpec

trait MovementSpecRepo extends DomainRepo[MovementSpec, MovementSpec]:
  def findByLedgerId(ledgerId:     ID): Txn[Vector[MovementSpec]]
  def findByFileFeedId(fileFeedId: ID): Txn[Vector[MovementSpec]]
