package anseliq.adapter.driven

import anseliq.domain.service.ClockService
import anseliq.common.model.model.ID
import anseliq.domain.model
import anseliq.domain.model.Ledger
import anseliq.domain.port.driven.{LedgerRepo, Txn}
import com.augustnagro.magnum.{DbCon, sql}

import java.util.UUID

class LedgerRepoLive(clock: ClockService) extends AuditedRepo[Ledger, UUID](clock) with LedgerRepo:
  private val f = Ledger.Table

  override def findByName(name: String): Txn[Option[Ledger]] =
    sql"""
        SELECT ${f.all}
        FROM $f AS f
        WHERE name = $name
      """.query[Ledger].run().headOption

  override def findById(id: ID)(using DbCon): Option[Ledger] =
    val frag = sql"""
        SELECT ${f.all}, created_at, updated_at
        FROM $f AS f
        WHERE id = ${id}
      """
    frag.query[Ledger].run().headOption
