package perfectpair.adapter.driven

import com.augustnagro.magnum.*
import perfectpair.common.model.model.{ID, Money}
import perfectpair.domain.model
import perfectpair.domain.model.Relationship.{ManyToMany, ManyToOne, OneToMany, OneToOne}
import perfectpair.domain.model.{Movement, Reconciliation, Relationship}
import perfectpair.domain.port.driven.{MovementInID, MovementOutID, MovementRepo, Txn}

import java.util.UUID

class MovementRepoLive extends Repo[Movement, Movement, UUID] with MovementRepo:
  val m = Movement.Table
  val r = Reconciliation.Table

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
    sqlCriteria:  String,
    relationship: Relationship = OneToOne
  ): Txn[Vector[(MovementInID, MovementOutID)]] =
    val criteria = SqlLiteral(sqlCriteria)
    val orderingClause = SqlLiteral(
      relationship match
        case OneToOne | OneToMany | ManyToMany => " order by move_in.id, move_out.id"
        case ManyToOne                         => " order by move_out.id, move_in.id"
    )

    sql"""
      SELECT move_in.id, move_out.id
      FROM $m AS move_in
      JOIN $m AS move_out
      ON move_in.in_account_id = move_out.out_account_id
      where 1 = 1
      AND $criteria
      $orderingClause
    """.query[(String, String)].run()
