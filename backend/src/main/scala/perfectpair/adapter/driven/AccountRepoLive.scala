package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.common.model.model.ID
import perfectpair.domain.model
import perfectpair.domain.model.Account
import perfectpair.domain.port.driven.{AccountRepo, Txn}

import java.util.UUID

class AccountRepoLive extends AuditableRepo[Account, Account, UUID] with AccountRepo:
  val a = Account.Table

  def findNominalWithNonZeroBalance(fundFlowID: ID): Txn[Vector[Account]] =
    sql"""
      SELECT ${a.all}
      FROM $a AS a
      WHERE balance <> 0
      AND a.fund_flow_id = $fundFlowID
    """.query[Account].run()

  def findByFundFlowAndAccountName(
    fundFlowID:  ID,
    accountName: String
  ): Txn[Option[Account]] =
    sql"""
      SELECT ${a.all}
      FROM $a AS a
      WHERE fund_flow_id = $fundFlowID
      AND name = $accountName
    """.query[Account].run().headOption

  def findByFundFlow(
    fundFlowID: ID
  ): Txn[Vector[Account]] =
    sql"""
      SELECT ${a.all}
      FROM $a AS a
      WHERE fund_flow_id = $fundFlowID
    """.query[Account].run()

  def findAll(): Txn[Vector[Account]] =
    sql"""
      SELECT ${a.all}
      FROM $a AS a
    """.query[Account].run()
