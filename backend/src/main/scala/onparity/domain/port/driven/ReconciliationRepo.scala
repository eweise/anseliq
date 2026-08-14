package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.Reconciliation

trait ReconciliationRepo extends DomainRepo[Reconciliation, Reconciliation]:
  def findByReconSpecID(reconSpecID: ID): Txn[Vector[Reconciliation]]
  def findInProgressBySpecId(specId: ID): Txn[Vector[Reconciliation]]
