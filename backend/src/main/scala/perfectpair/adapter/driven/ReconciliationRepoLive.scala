package perfectpair.adapter.driven

import com.augustnagro.magnum.Repo
import perfectpair.domain.model
import perfectpair.domain.model.Reconciliation
import perfectpair.domain.port.driven.ReconciliationRepo

import java.util.UUID

class ReconciliationRepoLive extends Repo[Reconciliation, Reconciliation, UUID] with ReconciliationRepo
