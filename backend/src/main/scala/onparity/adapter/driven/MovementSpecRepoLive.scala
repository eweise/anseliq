package onparity.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import onparity.common.model.model.ID
import onparity.domain.model.MovementSpec
import onparity.domain.port.driven.{MovementSpecRepo, Txn}

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

  def findByFileFeedId(fileFeedId: ID): Txn[Vector[MovementSpec]] =
    sql"""
      SELECT ${ms.all}
      FROM $ms
      WHERE file_feed_id = $fileFeedId
    """.query[MovementSpec].run()
