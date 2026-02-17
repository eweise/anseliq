package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.domain.model
import perfectpair.domain.model.FundFlowConfig
import perfectpair.domain.port.driven.{FundFlowConfigRepo, Txn}

import java.util.UUID

class FundFlowConfigRepoLive extends Repo[FundFlowConfig, FundFlowConfig, UUID] with FundFlowConfigRepo:
  private val f = FundFlowConfig.Table

  override def findByName(name: String): Txn[Option[FundFlowConfig]] =
    sql"""
        SELECT ${f.all}
        FROM $f AS f
        WHERE name = $name
      """.query[FundFlowConfig].run().headOption
