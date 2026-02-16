package perfectpair.adapter.driven

import com.augustnagro.magnum.{BatchUpdateResult, DbCon, Repo, SqlLiteral, batchUpdate, sql}
import perfectpair.common.model.model.{ID, Properties}
import perfectpair.domain.model
import perfectpair.domain.port.driven.{MovementRepo, Txn}
import perfectpair.domain.model.Movement

import java.util.UUID

class MovementRepoLive extends Repo[Movement, Movement, UUID] with MovementRepo:
  val m = Movement.Table

  override def insertAll(movements: Iterable[Movement])(using DbCon): Unit =
    batchUpdate(movements): movement =>
      sql"""
           INSERT INTO $m ${m.insertColumns}
           VALUES ($movement)
           ON CONFLICT(reference_id) DO NOTHING""".update

  override def insert(movement: Movement)(using DbCon): Unit =
    sql"""
         INSERT INTO $m ${m.insertColumns}
         VALUES ($movement)
         ON CONFLICT(reference_id) DO NOTHING""".update.run()

  def findUnreconciledByProperty(key: String, value: String): Txn[Vector[Movement]] =
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

  def findEqualityMatches(
    sqlCriteria: String
  ): Txn[Vector[(String, String)]] =
    val criteria = SqlLiteral(sqlCriteria)
    sql"""
      SELECT move_in.id, move_out.id
      FROM $m AS move_in
      JOIN $m AS move_out
      ON move_in.in_account_id = move_out.out_account_id
      where 1 = 1
      AND $criteria
    """.query[(String, String)].run()
