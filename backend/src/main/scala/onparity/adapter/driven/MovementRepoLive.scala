package onparity.adapter.driven

import com.augustnagro.magnum.*
import onparity.common.model.model.ID
import onparity.domain.model
import onparity.domain.model.Relationship.{ManyToMany, OneToMany, OneToOne}
import onparity.domain.model.{Account, Movement, MovementSearchCriteria, Reconciliation, ReconciliationFilter, ReconciliationStatus, Relationship}
import onparity.domain.port.driven.{MovementInID, MovementOutID, MovementRepo, Txn, UnreconciledCursor}

import java.util.UUID
import javax.sql.DataSource

class MovementRepoLive extends Repo[Movement, Movement, UUID] with MovementRepo:
  val m    = Movement.Table
  val r    = Reconciliation.Table
  val acct = Account.Table

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

  def countByAccount(accountID: ID): Txn[Int] =
    sql"""
      SELECT count(*)
      FROM $m AS a
      WHERE (in_account_id = $accountID OR out_account_id = $accountID)
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

  // Deliberately not a Txn: transact(transactor) manages one connection for the duration of a
  // single callback, but Magnum only ever hands out a DbCon through such a callback (DbCon's
  // constructor is private[magnum] — there's no public way to wrap a connection we hold ourselves).
  // So the cursor's whole lifetime — DECLARE, every fetchPage, CLOSE — has to live inside one
  // connect(dataSource) callback, which is why this takes `use` as a continuation instead of
  // returning a standalone closeable handle. DECLARE ... CURSOR WITH HOLD, committed immediately
  // below, is what makes the pagination itself cheap: Postgres materializes the full sorted result
  // into a temp file/tuplestore at that commit, so the expensive sort happens exactly once, not
  // once per fetchPage, and no long-running transaction/snapshot is held beyond the moment it takes
  // to run and materialize that query.
  def withUnreconciledCursor[T](
    dataSource:     DataSource,
    movementSpecId: ID,
    matchKeyExpr:   String,
    afterKey:       Option[String],
    pageSize:       Int
  )(use: UnreconciledCursor => T): T =
    val keyExpr = SqlLiteral(matchKeyExpr)
    // Cursor names can't be bind parameters, so this is spliced as a literal like matchKeyExpr
    // above; safe because it's generated here, not user input.
    val cursorName = SqlLiteral(s"movement_cur_${UUID.randomUUID.toString.replace("-", "")}")
    // FETCH's row count is a utility-statement argument, not a query parameter position Postgres
    // accepts a bind for, so pageSize is spliced as a literal too (safe: caller-controlled Int).
    val fetchCount = SqlLiteral(pageSize.toString)
    val after      = afterKey.getOrElse("")

    connect(dataSource):
      // connect()'s callback is a context function (DbCon ?=> T, same shape as this codebase's
      // own Txn = DbTx ?=> T) — summon[DbCon] is the established idiom here for reaching the given
      // connection directly, already used the same way in InMemoryReconciler's commitBatch.
      val dbCon = summon[DbCon]
      // connect() doesn't manage a transaction the way transact() does, so autocommit starts at
      // the driver default (true) — DECLARE CURSOR requires an open transaction, hence this.
      dbCon.connection.setAutoCommit(false)

      sql"""
        DECLARE $cursorName CURSOR WITH HOLD FOR
        SELECT ${m.all}
        FROM $m AS a
        WHERE reconciliation_id IS NULL
        AND movement_spec_id = $movementSpecId
        AND $keyExpr > $after
        ORDER BY $keyExpr
      """.update.run()
      dbCon.connection.commit()

      val cursor = new UnreconciledCursor:
        def fetchPage(): Vector[Movement] =
          val page = sql"FETCH FORWARD $fetchCount FROM $cursorName".query[Movement].run()
          dbCon.connection.commit()
          page

      try use(cursor)
      finally
        sql"CLOSE $cursorName".update.run()
        dbCon.connection.commit()

  // Containment (@>), not ->'props'->>'file_source_entry' = ..., so movement_meta_data_index (a
  // GIN index, same as movement_props_index) actually backs this — a plain equality comparison on
  // the extracted text value wouldn't be index-backed the way containment is.
  def findByFileSourceEntry(fileSourceEntry: String): Txn[Vector[Movement]] =
    val containment = upickle.default.write(Map("props" -> Map("file_source_entry" -> fileSourceEntry)))
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE a.meta_data @> $containment::jsonb
      ORDER BY a.created_at
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

  // Every optional filter uses the `($param IS NULL OR condition)` idiom in one static,
  // bind-parameterized query — unlike matchKeyExpr elsewhere in this file (which needs SqlLiteral
  // because that SQL's *structure* genuinely varies), here only which predicate is *active*
  // varies, not its shape, so no dynamic SQL string-building is needed. The Properties filter
  // (an arbitrary-size key/value map) collapses to a single jsonb containment check (`@>`) against
  // one bind param instead of one fragment per key — Postgres checks every key/value pair in one
  // comparison. The explicit `::type` casts on each nullable bind param exist because Postgres
  // can't infer a bind parameter's type from `IS NULL` alone.
  def search(c: MovementSearchCriteria): Txn[Vector[Movement]] =
    val propsJson   = if c.properties.isEmpty then None else Some(upickle.default.write(c.properties))
    val anyRecon    = c.reconciliation == ReconciliationFilter.All
    val openBalance = c.reconciliation == ReconciliationFilter.OpenBalance
    val inProgress  = ReconciliationStatus.InProgress
    val mismatched  = ReconciliationStatus.Mismatched
    sql"""
      SELECT ${m.all}
      FROM $m AS a
      WHERE (
        a.in_account_id IN (SELECT id FROM $acct WHERE fund_flow_id = ${c.fundFlowId})
        OR a.out_account_id IN (SELECT id FROM $acct WHERE fund_flow_id = ${c.fundFlowId})
      )
      AND (${c.accountId}::uuid IS NULL OR a.in_account_id = ${c.accountId} OR a.out_account_id = ${c.accountId})
      AND (${c.amount}::numeric IS NULL OR a.amount = ${c.amount})
      AND (${c.movementSpecId}::uuid IS NULL OR a.movement_spec_id = ${c.movementSpecId})
      AND ($propsJson::jsonb IS NULL OR a.properties -> 'props' @> $propsJson::jsonb)
      AND (${c.fileSourceQuery}::text IS NULL
           OR a.meta_data -> 'props' ->> 'file_source_entry' ILIKE '%' || ${c.fileSourceQuery} || '%')
      AND ($anyRecon
           OR a.reconciliation_id IS NULL
           OR ($openBalance AND a.reconciliation_id IN (SELECT id FROM $r WHERE status IN ($inProgress, $mismatched))))
      AND (${c.createdFrom}::timestamptz IS NULL OR a.created_at >= ${c.createdFrom})
      AND (${c.createdTo}::timestamptz IS NULL OR a.created_at < ${c.createdTo})
      AND (${c.movementType}::text IS NULL OR a.movement_type = ${c.movementType})
      ORDER BY a.created_at DESC
      LIMIT 1000
    """.query[Movement].run()

  // Bulk-links each movement to its own reconciliation in one statement, touching only the
  // reconciliation_id column — unlike the generic updateAll (which rewrites every column,
  // including the full properties JSONB blob, and fires once per row via JDBC batching, i.e.
  // still one statement execution per row server-side). Embedding the ids as literal SQL text
  // (rather than bind parameters) is safe here specifically because ID is always a UUID: its
  // toString is a fixed, closed hex+hyphen format that cannot contain quotes or SQL
  // metacharacters, unlike arbitrary user-supplied strings.
  // The `reconciliation_id IS NULL` guard stops a movement that was reconciled elsewhere (e.g. a
  // manual match) after the caller read it from being silently re-linked. Any skipped row fails the
  // whole call rather than being dropped, because callers have already built reconciliations and
  // ledger deltas that assume every movement was linked — throwing rolls their transaction back.
  // DBError (retryable) since a rerun re-reads unreconciled movements and won't see that row again.
  def setReconciliationIds(idPairs: Iterable[(ID, ID)]): Txn[Unit] =
    if idPairs.nonEmpty then
      val valuesClause = SqlLiteral(
        idPairs.map { case (movementId, reconciliationId) => s"('$movementId'::uuid, '$reconciliationId'::uuid)" }
          .mkString(", ")
      )
      val updated = sql"""
        UPDATE $m AS a
        SET reconciliation_id = v.reconciliation_id
        FROM (VALUES $valuesClause) AS v(id, reconciliation_id)
        WHERE a.id = v.id
        AND a.reconciliation_id IS NULL
      """.update.run()
      if updated != idPairs.size then
        throw model.DBError(
          s"setReconciliationIds linked $updated of ${idPairs.size} movements; the rest are missing or already reconciled"
        )
