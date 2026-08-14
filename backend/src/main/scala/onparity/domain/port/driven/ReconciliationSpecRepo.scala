package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.ReconciliationSpec

trait ReconciliationSpecRepo extends DomainRepo[ReconciliationSpec, ReconciliationSpec]:
  def findByAccountID(accountID: ID): Txn[Vector[ReconciliationSpec]]
