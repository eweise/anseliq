package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.Reconciliation

trait ReconciliationRepo extends DomainRepo[Reconciliation, Reconciliation]:
  def findByReconSpecID(reconSpecID: ID): Txn[Vector[Reconciliation]]
  def findInProgressBySpecId(specId: ID): Txn[Vector[Reconciliation]]

  // Spans every reconciliation spec belonging to the account (not just one), most recent first —
  // for an account-level "recent activity" view rather than the spec-scoped list.
  def findByAccountID(accountId: ID, limit: Int): Txn[Vector[Reconciliation]]
