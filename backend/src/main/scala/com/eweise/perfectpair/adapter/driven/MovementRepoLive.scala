package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, SqlLiteral, sql}
import com.eweise.perfectpair.common.model.model.{ID, Properties}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.{Movement}
import com.eweise.perfectpair.domain.port.driven.{MovementRepo, Txn}

import java.util.UUID

class MovementRepoLive extends Repo[model.Movement, model.Movement, UUID] with MovementRepo:
  val m = Movement.Table

  override def insert(movement: Movement)(using DbCon): Unit =
    sql"""
         INSERT INTO $m ${m.insertColumns}
         VALUES ($movement)
         ON CONFLICT(reference_id) DO NOTHING""".update.run()

  def findUnreconciledByProperty(key : String, value:String): Txn[Vector[Movement]] =
    val whereProps = SqlLiteral(s"""where properties @> '{"props":{"$key":"$value"}}'""")
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      $whereProps
      ORDER BY created_at
    """.query[Movement].run()


  def findUnReconciledByAccount(accountID: ID): Txn[Vector[Movement]] =
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE reconciliation_id IS NULL
      AND (in_account_id = $accountID OR out_account_id = $accountID)
      ORDER BY created_at
    """.query[Movement].run()

  def findByAccount(accountID: ID): Txn[Vector[Movement]] =
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE (in_account_id = $accountID OR out_account_id = $accountID)
      ORDER BY created_at
    """.query[Movement].run()
