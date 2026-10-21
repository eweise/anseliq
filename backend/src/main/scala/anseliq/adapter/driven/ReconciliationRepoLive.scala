package anseliq.adapter.driven

import anseliq.domain.service.ClockService
import anseliq.common.model.model.ID
import anseliq.domain.model
import anseliq.domain.model.{Reconciliation, ReconciliationStatus}
import com.augustnagro.magnum.{Repo, sql}
// Repo's generated update binds each column with a codec summoned here, not via Reconciliation's
// derived codec, and silently falls back to setObject (which Postgres rejects) when none is in
// scope — so the jsonb AppliedRules codec in Reconciliation's companion has to be imported.
import anseliq.domain.model.Reconciliation.given
import anseliq.domain.port.driven.{ReconciliationRepo, StatusTotals, Txn}

import java.util.UUID

class ReconciliationRepoLive(clock: ClockService) extends AuditedRepo[Reconciliation, UUID](clock) with ReconciliationRepo:
  val r = Reconciliation.Table

  def findByReconSpecID(reconSpecID: ID): Txn[Vector[Reconciliation]] =
    sql"""
      SELECT ${r.all}
      FROM $r AS a
      WHERE spec_id = $reconSpecID
      ORDER BY created_at
    """.query[Reconciliation].run()

  // AppliedRules.Manual has no fields, so it's matched however upickle wrote it: a bare string or
  // a {"$$type": ...} object. Suggested has fields, so it's always an object.
  def findRecentManualOrSuggested(reconSpecID: ID, limit: Int): Txn[Vector[Reconciliation]] =
    sql"""
      SELECT ${r.all}
      FROM $r AS a
      WHERE spec_id = $reconSpecID
      AND status = ${ReconciliationStatus.Final}
      AND (applied_rules = '"Manual"'::jsonb OR applied_rules ->> '$$type' IN ('Manual', 'Suggested'))
      ORDER BY created_at DESC
      LIMIT $limit
    """.query[Reconciliation].run()

  def findSuggested(reconSpecID: ID): Txn[Vector[Reconciliation]] =
    sql"""
      SELECT ${r.all}
      FROM $r AS a
      WHERE spec_id = $reconSpecID
      AND status = ${ReconciliationStatus.Final}
      AND applied_rules ->> '$$type' = 'Suggested'
    """.query[Reconciliation].run()

  def findBySpecIdAndStatus(specId: ID, status: ReconciliationStatus): Txn[Vector[Reconciliation]] =
    sql"""
      SELECT ${r.all}
      FROM $r AS a
      WHERE spec_id = $specId
      AND status = $status
      ORDER BY created_at
    """.query[Reconciliation].run()

  def findByAccountID(
    accountId: ID,
    limit:     Int,
    status:    Option[ReconciliationStatus] = None
  ): Txn[Vector[Reconciliation]] =
    sql"""
      SELECT ${r.alias("a").all}
      FROM $r AS a
      JOIN reconciliation_spec rs ON rs.id = a.spec_id
      WHERE rs.account_id = $accountId
      AND ($status::text IS NULL OR a.status = $status)
      ORDER BY a.created_at DESC
      LIMIT $limit
    """.query[Reconciliation].run()

  def countByStatusForAccountID(accountId: ID): Txn[Map[ReconciliationStatus, Int]] =
    sql"""
      SELECT a.status, count(*)::int
      FROM $r AS a
      JOIN reconciliation_spec rs ON rs.id = a.spec_id
      WHERE rs.account_id = $accountId
      GROUP BY a.status
    """.query[(ReconciliationStatus, Int)].run().toMap

  // Joins through the movements rather than summing reconciliation.balance: that column is
  // (in-spec amounts − out-spec amounts) for the group, which isn't necessarily signed the way the
  // account's own balance is; the movements' in/out accounts give the account's view directly.
  def openTotalsByAccountID(accountId: ID): Txn[Vector[StatusTotals]] =
    val inProgress = ReconciliationStatus.InProgress
    val mismatched = ReconciliationStatus.Mismatched
    sql"""
      SELECT a.status,
             count(DISTINCT a.id)::int,
             coalesce(sum(CASE WHEN m.in_account_id = $accountId THEN m.amount ELSE -m.amount END), 0)
      FROM $r AS a
      JOIN reconciliation_spec rs ON rs.id = a.spec_id
      JOIN movement m ON m.reconciliation_id = a.id
      WHERE rs.account_id = $accountId
      AND (m.in_account_id = $accountId OR m.out_account_id = $accountId)
      AND a.status IN ($inProgress, $mismatched)
      GROUP BY a.status
    """.query[(ReconciliationStatus, Int, BigDecimal)].run().map(StatusTotals.apply.tupled)
