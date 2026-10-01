package onparity.domain.port.driven

import onparity.common.model.model.{ID, Money}
import onparity.domain.model.{Reconciliation, ReconciliationStatus}

// One status's share of a recon account's balance: how many reconciliations are in it, and the
// net of their movements as the account sees them (in = +, out = −) — the same sign the ledger
// uses for the account's balance, so these add up with its unreconciledBalance.
case class StatusTotals(status: ReconciliationStatus, count: Int, balance: Money)

trait ReconciliationRepo extends DomainRepo[Reconciliation, Reconciliation]:
  def findByReconSpecID(reconSpecID: ID): Txn[Vector[Reconciliation]]
  def findBySpecIdAndStatus(specId: ID, status: ReconciliationStatus): Txn[Vector[Reconciliation]]

  // Spans every reconciliation spec belonging to the account (not just one), most recent first —
  // for an account-level "recent activity" view rather than the spec-scoped list. `status`
  // narrows it to one status (e.g. the Overview's "In progress" tile); None returns every status.
  def findByAccountID(
    accountId: ID,
    limit:     Int,
    status:    Option[ReconciliationStatus] = None
  ): Txn[Vector[Reconciliation]]

  // Totals for the account's InProgress and Mismatched reconciliations — the ones whose balance
  // hasn't netted to zero, so they still make up part of the account's balance. A status with no
  // reconciliations is simply absent.
  def openTotalsByAccountID(accountId: ID): Txn[Vector[StatusTotals]]
