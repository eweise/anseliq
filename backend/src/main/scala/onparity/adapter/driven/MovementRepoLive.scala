package onparity.adapter.driven

import com.augustnagro.magnum.*
import onparity.common.model.model.ID
import onparity.domain.model
import onparity.domain.model.Relationship.{ManyToMany, OneToMany, OneToOne}
import onparity.domain.model.{Movement, Reconciliation, Relationship}
import onparity.domain.port.driven.{MovementInID, MovementOutID, MovementRepo, Txn}

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

  def findByReconciliationID(reconciliationID: ID): Txn[Vector[Movement]] =
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE 1 = 1
      AND reconciliation_id = $reconciliationID
      ORDER BY created_at
    """.query[Movement].run()

  def findUnReconciledByMovementSpec(movementSpecID: ID): Txn[Vector[Movement]] =
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE reconciliation_id IS NULL
      AND movement_spec_id = $movementSpecID
      ORDER BY created_at
    """.query[Movement].run()

  def findByAccount(accountID: ID): Txn[Vector[Movement]] =
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE (in_account_id = $accountID OR out_account_id = $accountID)
      ORDER BY created_at
    """.query[Movement].run()

  def countUnreconciledByAccount(accountID: ID): Txn[Int] =
    sql"""
      SELECT count(*)
      FROM $m AS a
      WHERE (in_account_id = $accountID OR out_account_id = $accountID)
      AND reconciliation_id IS NULL
    """.query[Int].run().head

  def findEqualityMatches(
    sqlCriteria:       String,
    inMovementSpecId:  ID,
    outMovementSpecId: ID,
    relationship:      Relationship = OneToOne
  ): Txn[Vector[(MovementInID, MovementOutID)]] =
    val criteria = SqlLiteral(sqlCriteria)
    val orderingClause = SqlLiteral(
      relationship match
        case OneToOne | OneToMany | ManyToMany => " order by move_in.id, move_out.id"
    )

    sql"""
      SELECT move_in.id, move_out.id
      FROM $m AS move_in
      JOIN $m AS move_out
      ON move_in.in_account_id = move_out.out_account_id
      where 1 = 1
      AND move_in.movement_spec_id = $inMovementSpecId
      AND move_out.movement_spec_id = $outMovementSpecId
      AND $criteria
      $orderingClause
    """.query[(String, String)].run()

  def findUnreconciledPage(
    movementSpecId: ID,
    matchKeyExpr:   String,
    afterKey:       Option[String],
    pageSize:       Int
  ): Txn[Vector[Movement]] =
    val keyExpr = SqlLiteral(matchKeyExpr)
    // No lower bound needed on the first page: match keys come from real business
    // identifiers and are never empty strings in practice, so "> ''" is equivalent
    // to "no filter" while still letting afterKey be a plain, safely-parameterized
    // bind value rather than a conditionally-included raw SQL fragment.
    val after = afterKey.getOrElse("")
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE reconciliation_id IS NULL
      AND movement_spec_id = $movementSpecId
      AND $keyExpr > $after
      ORDER BY $keyExpr
      LIMIT $pageSize
    """.query[Movement].run()

  def findUnreconciledByExactMatchKey(
    movementSpecId: ID,
    matchKeyExpr:   String,
    keyValue:       String
  ): Txn[Vector[Movement]] =
    val keyExpr = SqlLiteral(matchKeyExpr)
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE reconciliation_id IS NULL
      AND movement_spec_id = $movementSpecId
      AND $keyExpr = $keyValue
    """.query[Movement].run()

  // Same "embed UUIDs as literal SQL text" reasoning as setReconciliationIds below: safe because
  // ID is always a UUID, whose toString can't contain quotes or SQL metacharacters.
  def findExistingReferenceIds(referenceIds: Iterable[ID]): Txn[Set[ID]] =
    if referenceIds.isEmpty then Set.empty
    else
      val inClause = SqlLiteral(referenceIds.map(id => s"'$id'::uuid").mkString(", "))
      sql"""
        SELECT reference_id
        FROM $m AS a
        WHERE reference_id IN ($inClause)
      """.query[ID].run().toSet

  // Bulk-links each movement to its own reconciliation in one statement, touching only the
  // reconciliation_id column — unlike the generic updateAll (which rewrites every column,
  // including the full properties JSONB blob, and fires once per row via JDBC batching, i.e.
  // still one statement execution per row server-side). Embedding the ids as literal SQL text
  // (rather than bind parameters) is safe here specifically because ID is always a UUID: its
  // toString is a fixed, closed hex+hyphen format that cannot contain quotes or SQL
  // metacharacters, unlike arbitrary user-supplied strings.
  def setReconciliationIds(idPairs: Iterable[(ID, ID)]): Txn[Unit] =
    if idPairs.nonEmpty then
      val valuesClause = SqlLiteral(
        idPairs
          .map { case (movementId, reconciliationId) => s"('$movementId'::uuid, '$reconciliationId'::uuid)" }
          .mkString(", ")
      )
      sql"""
        UPDATE $m AS a
        SET reconciliation_id = v.reconciliation_id
        FROM (VALUES $valuesClause) AS v(id, reconciliation_id)
        WHERE a.id = v.id
      """.update.run()
