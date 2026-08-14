package onparity.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import onparity.common.model.model.ID
import onparity.domain.model
import onparity.domain.model.{Reconciliation, ReconciliationStatus}
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
