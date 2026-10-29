package anseliq.adapter.driven

import anseliq.domain.service.ClockService
import anseliq.common.model.model.ID
import anseliq.domain.model
import anseliq.domain.model.ReconciliationSpec
import anseliq.domain.port.driven.{ReconciliationSpecRepo, Txn}
import com.augustnagro.magnum.sql

import java.util.UUID

class ReconciliationSpecRepoLive(clock: ClockService)
    extends AuditedRepo[ReconciliationSpec, UUID](clock)
    with ReconciliationSpecRepo:
  private val t = ReconciliationSpec.Table

  override def findByAccountId(accountId: ID): Txn[Vector[ReconciliationSpec]] =
    sql"""
          SELECT ${t.all}
          FROM $t AS t
          WHERE account_id = $accountId
        """.query[ReconciliationSpec].run()
