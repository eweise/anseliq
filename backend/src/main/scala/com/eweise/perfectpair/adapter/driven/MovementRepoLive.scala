package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import com.eweise.perfectpair.common.model.model.ID
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.Direction.{IN, OUT}
import com.eweise.perfectpair.domain.model.{Direction, Movement}
import com.eweise.perfectpair.domain.port.driven.{MovementRepo, Txn}

import java.util.UUID

class MovementRepoLive extends Repo[model.Movement, model.Movement, UUID] with MovementRepo:
  val m = Movement.Table

  override def insert(movement: Movement)(using DbCon): Unit =
    sql"""
         INSERT INTO $m ${m.insertColumns}
         VALUES ($movement)
         ON CONFLICT(reference_id) DO NOTHING""".update.run()

  def findUnReconciledByAccountAndDirection(accountID: ID, direction: Direction): Txn[Vector[Movement]] =
    val directionFrag = direction match
      case IN  => sql"""in_account_id """
      case OUT => sql"""out_account_id """

    sql"""
      SELECT ${m.all}
      FROM $m as a
      where status <> 'reconciled'
      and ${directionFrag} = $accountID
      order by created_at
       """.query[Movement].run()

  def findUnReconciledByAccount(accountID: ID): Txn[Vector[Movement]] =
    sql"""
      SELECT ${m.all}
      FROM $m as a
      where reconciliation_id is not null
      and (in_account_id = $accountID or out_account_id = $accountID)
      order by created_at
       """.query[Movement].run()

  def findByAccount(accountID: ID): Txn[Vector[Movement]] =
    sql"""
      SELECT ${m.all}
      FROM $m as a
      where (in_account_id = $accountID or out_account_id = $accountID)
      order by created_at
       """.query[Movement].run()
