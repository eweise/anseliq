package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.Repo
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.ReconciliationSpec
import com.eweise.perfectpair.domain.port.driven.ReconciliationSpecRepo

import java.util.UUID

class ReconciliationSpecRepoLive extends Repo[model.ReconciliationSpec, model.ReconciliationSpec, UUID] with ReconciliationSpecRepo
