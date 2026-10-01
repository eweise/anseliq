package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.{Reconciliation, ReconciliationStatus}

trait ReconciliationRepo extends DomainRepo[Reconciliation, Reconciliation]:
  def findByReconSpecID(reconSpecID: ID): Txn[Vector[Reconciliation]]
  def findInProgressBySpecId(specId: ID): Txn[Vector[Reconciliation]]

  // Spans every reconciliation spec belonging to the account (not just one), most recent first —
  // for an account-level "recent activity" view rather than the spec-scoped list. `status`
  // narrows it to one status (e.g. the Overview's "In progress" tile); None returns every status.
  def findByAccountID(
    accountId: ID,
    limit:     Int,
    status:    Option[ReconciliationStatus] = None
  ): Txn[Vector[Reconciliation]]

  // InProgress reconciliations across every spec belonging to the account — the ones whose
  // balance hasn't netted to zero yet, so they still make up part of the account's balance.
  def countInProgressByAccountID(accountId: ID): Txn[Int]
