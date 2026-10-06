package anseliq.adapter.driven

import anseliq.common.model.model.ID
import anseliq.domain.model
import anseliq.domain.model.Account
import anseliq.domain.port.driven.{AccountRepo, Txn}
import com.augustnagro.magnum.{DbCon, Repo, sql}

import java.util.UUID

class AccountRepoLive extends Repo[Account, Account, UUID] with AccountRepo:
  val a = Account.Table

  def findByIdForUpdate(id: ID): Txn[Option[Account]] =
    sql"""
        SELECT ${a.all}
        FROM $a AS a
        WHERE id = $id
        FOR UPDATE
      """.query[Account].run().headOption

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
