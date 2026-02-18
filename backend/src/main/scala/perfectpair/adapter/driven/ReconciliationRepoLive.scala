package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.common.model.model.ID
import perfectpair.domain.model
import perfectpair.domain.model.Reconciliation
import perfectpair.domain.port.driven.{ReconciliationRepo, Txn}

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
