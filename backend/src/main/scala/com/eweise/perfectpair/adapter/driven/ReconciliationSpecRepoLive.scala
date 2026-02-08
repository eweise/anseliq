package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import com.eweise.perfectpair.common.model.model.ID
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.ReconciliationSpec
import com.eweise.perfectpair.domain.port.driven.{ReconciliationSpecRepo, Txn}

import java.util.UUID

class ReconciliationSpecRepoLive extends Repo[model.ReconciliationSpec, model.ReconciliationSpec, UUID] with ReconciliationSpecRepo:
  private val t = ReconciliationSpec.Table

  override def findByFundFlow(fundFlowId: ID): Txn[Vector[ReconciliationSpec]] =
    sql"""
          SELECT ${t.all}
          FROM $t AS t
          WHERE fund_flow_id = $fundFlowId
        """.query[ReconciliationSpec].run()
