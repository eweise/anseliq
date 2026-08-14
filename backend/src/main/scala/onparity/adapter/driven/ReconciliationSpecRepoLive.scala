package onparity.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import onparity.common.model.model.ID
import onparity.domain.model
import onparity.domain.model.ReconciliationSpec
import onparity.domain.port.driven.{ReconciliationSpecRepo, Txn}

import java.util.UUID

class ReconciliationSpecRepoLive extends Repo[ReconciliationSpec, ReconciliationSpec, UUID] with ReconciliationSpecRepo:
  private val t = ReconciliationSpec.Table

  override def findByAccountID(accountID: ID): Txn[Vector[ReconciliationSpec]] =
    sql"""
          SELECT ${t.all}
          FROM $t AS t
          WHERE account_id = $accountID
        """.query[ReconciliationSpec].run()
