package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.Repo
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.port.driven.FundFlowConfigRepo
import model.{FundFlowConfig, ID}

import java.util.UUID

class FundFlowConfigRepoLive extends Repo[model.FundFlowConfig, model.FundFlowConfig, UUID] with FundFlowConfigRepo
