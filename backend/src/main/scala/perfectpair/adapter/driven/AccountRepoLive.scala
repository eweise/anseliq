package perfectpair.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import perfectpair.common.model.model.ID
import perfectpair.domain.model
import perfectpair.domain.model.Account
import perfectpair.domain.port.driven.{AccountRepo, Txn}

import java.util.UUID

class AccountRepoLive extends Repo[Account, Account, UUID] with AccountRepo:
  val a = Account.Table

  override def findById(id: UUID)(using DbCon): Option[Account] =
    sql"""
      SELECT ${a.all}, balance, created_at, updated_at
      FROM $a AS a
      WHERE id = $id
    """.query[Account].run().headOption

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

  def isReconAccount(accountID: ID): Txn[Boolean] =
    sql"""
      SELECT
          CASE
              WHEN EXISTS(
                SELECT *
                FROM recon.movement_spec s
                WHERE s.in_account_id = $accountID)
              AND EXISTS(
                SELECT *
                FROM recon.movement_spec s 
                WHERE s.out_account_id = $accountID)
              THEN true
              ELSE false
          END AS is_recon_account
     """.query[Boolean].run().head
