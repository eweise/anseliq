package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.Repo
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.port.driven.ReconciliationRepo

import java.util.UUID

class ReconciliationRepoLive extends Repo[model.Reconciliation, model.Reconciliation, UUID] with ReconciliationRepo
