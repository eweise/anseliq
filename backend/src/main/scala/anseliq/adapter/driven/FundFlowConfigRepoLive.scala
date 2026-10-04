package anseliq.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import anseliq.common.model.model.ID
import anseliq.domain.model
import anseliq.domain.model.FundFlowConfig
import anseliq.domain.port.driven.{FundFlowConfigRepo, Txn}

import java.util.UUID

class FundFlowConfigRepoLive extends Repo[FundFlowConfig, FundFlowConfig, UUID] with FundFlowConfigRepo:
  private val f = FundFlowConfig.Table

  override def findByName(name: String): Txn[Option[FundFlowConfig]] =
    sql"""
        SELECT ${f.all}
        FROM $f AS f
        WHERE name = $name
      """.query[FundFlowConfig].run().headOption

  override def findById(id: ID)(using DbCon): Option[FundFlowConfig] =
    val frag = sql"""
        SELECT ${f.all}, created_at, updated_at
        FROM $f AS f
        WHERE id = ${id}
      """
    frag.query[FundFlowConfig].run().headOption
