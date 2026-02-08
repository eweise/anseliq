package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import com.eweise.perfectpair.domain.model
import com.eweise.perfectpair.domain.model.FundFlowConfig
import com.eweise.perfectpair.domain.port.driven.{FundFlowConfigRepo, Txn}

import java.util.UUID

class FundFlowConfigRepoLive extends Repo[model.FundFlowConfig, model.FundFlowConfig, UUID] with FundFlowConfigRepo:
  private val f = FundFlowConfig.Table

  override def findByName(name: String): Txn[Option[FundFlowConfig]] =
    sql"""
        SELECT ${f.all}
        FROM $f AS f
        WHERE name = $name
      """.query[FundFlowConfig].run().headOption
