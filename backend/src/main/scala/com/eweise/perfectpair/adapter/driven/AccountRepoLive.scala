package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import com.eweise.perfectpair.common.model.model.ID
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.Account
import com.eweise.perfectpair.domain.port.driven.{AccountRepo, Txn}

import java.util.UUID

class AccountRepoLive extends AuditableRepo[model.Account, model.Account, UUID] with AccountRepo:
  val a = Account.Table

  def findNominalWithNonZeroBalance(fundFlowID: ID): Txn[Vector[model.Account]] =
    sql"""
      SELECT ${a.all}
      FROM $a AS a
      WHERE balance <> 0
      AND a.fund_flow_id = $fundFlowID
    """.query[Account].run()

  def findByFundFlowAndAccountName(
    fundFlowID: ID,
    accountName: String
  ): Txn[Option[model.Account]] =
    sql"""
      SELECT ${a.all}
      FROM $a AS a
      WHERE fund_flow_id = $fundFlowID
      AND name = $accountName
    """.query[Account].run().headOption

  def findByFundFlow(
    fundFlowID: ID
  ): Txn[Vector[model.Account]] =
    sql"""
      SELECT ${a.all}
      FROM $a AS a
      WHERE fund_flow_id = $fundFlowID
    """.query[Account].run()

  def findAll():Txn[Vector[model.Account]] =
    sql"""
      SELECT ${a.all}
      FROM $a AS a
    """.query[Account].run()
