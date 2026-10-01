package onparity.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import onparity.common.model.model.ID
import onparity.domain.model
import onparity.domain.model.{Reconciliation, ReconciliationStatus}
// Repo's generated update binds each column with a codec summoned here, not via Reconciliation's
// derived codec, and silently falls back to setObject (which Postgres rejects) when none is in
// scope — so the jsonb AppliedRules codec in Reconciliation's companion has to be imported.
import onparity.domain.model.Reconciliation.given
import onparity.domain.port.driven.{ReconciliationRepo, Txn}

import java.util.UUID

class ReconciliationRepoLive extends Repo[Reconciliation, Reconciliation, UUID] with ReconciliationRepo:
  val r = Reconciliation.Table

  def findByReconSpecID(reconSpecID: ID): Txn[Vector[Reconciliation]] =
    sql"""
      SELECT ${r.all}
      FROM $r AS a
      WHERE spec_id = $reconSpecID
      ORDER BY created_at
    """.query[Reconciliation].run()

  def findInProgressBySpecId(specId: ID): Txn[Vector[Reconciliation]] =
    val status = ReconciliationStatus.InProgress
    sql"""
      SELECT ${r.all}
      FROM $r AS a
      WHERE spec_id = $specId
      AND status = $status
      ORDER BY created_at
    """.query[Reconciliation].run()

  def findByAccountID(accountId: ID, limit: Int): Txn[Vector[Reconciliation]] =
    sql"""
      SELECT ${r.alias("a").all}
      FROM $r AS a
      JOIN reconciliation_spec rs ON rs.id = a.spec_id
      WHERE rs.account_id = $accountId
      ORDER BY a.created_at DESC
      LIMIT $limit
    """.query[Reconciliation].run()
