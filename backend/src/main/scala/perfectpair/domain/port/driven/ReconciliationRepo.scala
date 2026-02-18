package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model.Reconciliation

trait ReconciliationRepo extends DomainRepo[Reconciliation, Reconciliation]:
  def findByReconSpecID(reconSpecID: ID): Txn[Vector[Reconciliation]]
