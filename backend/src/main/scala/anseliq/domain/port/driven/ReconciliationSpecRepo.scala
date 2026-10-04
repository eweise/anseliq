package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.ReconciliationSpec

trait ReconciliationSpecRepo extends DomainRepo[ReconciliationSpec, ReconciliationSpec]:
  def findByAccountID(accountID: ID): Txn[Vector[ReconciliationSpec]]
