package perfectpair.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import perfectpair.common.model.model.ID
import perfectpair.domain.model.MovementSpec
import perfectpair.domain.port.driven.{MovementSpecRepo, Txn}

import java.util.UUID

class MovementSpecRepoLive extends Repo[MovementSpec, MovementSpec, UUID] with MovementSpecRepo:
  val ms = MovementSpec.Table

  def findByFundFlowId(fundFlowId: ID): Txn[Vector[MovementSpec]] =
    sql"""
      SELECT ms.*
      FROM $ms AS ms
      JOIN account a ON ms.in_account_id = a.id
      WHERE a.fund_flow_id = $fundFlowId
    """.query[MovementSpec].run()
