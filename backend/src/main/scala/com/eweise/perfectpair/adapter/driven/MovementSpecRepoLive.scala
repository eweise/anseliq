package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.Repo
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.port.driven.MovementSpecRepo

import java.util.UUID

class MovementSpecRepoLive extends Repo[model.MovementSpec, model.MovementSpec, UUID] with MovementSpecRepo
