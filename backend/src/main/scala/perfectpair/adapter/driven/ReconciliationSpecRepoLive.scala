package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.common.model.model.ID
import perfectpair.domain.model
import perfectpair.domain.port.driven.{ReconciliationSpecRepo, Txn}
import perfectpair.domain.model.ReconciliationSpec

import java.util.UUID

class ReconciliationSpecRepoLive extends Repo[ReconciliationSpec, ReconciliationSpec, UUID] with ReconciliationSpecRepo:
  private val t = ReconciliationSpec.Table

  override def findByFundFlow(fundFlowId: ID): Txn[Vector[ReconciliationSpec]] =
    sql"""
          SELECT ${t.all}
          FROM $t AS t
          WHERE fund_flow_id = $fundFlowId
        """.query[ReconciliationSpec].run()
