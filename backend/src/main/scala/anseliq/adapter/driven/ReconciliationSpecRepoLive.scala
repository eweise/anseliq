package anseliq.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import anseliq.common.model.model.ID
import anseliq.domain.model
import anseliq.domain.model.ReconciliationSpec
import anseliq.domain.port.driven.{ReconciliationSpecRepo, Txn}

import java.util.UUID

class ReconciliationSpecRepoLive extends Repo[ReconciliationSpec, ReconciliationSpec, UUID] with ReconciliationSpecRepo:
  private val t = ReconciliationSpec.Table

  override def findByAccountID(accountID: ID): Txn[Vector[ReconciliationSpec]] =
    sql"""
          SELECT ${t.all}
          FROM $t AS t
          WHERE account_id = $accountID
        """.query[ReconciliationSpec].run()
