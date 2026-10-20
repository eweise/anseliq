package anseliq.adapter.driven

import anseliq.domain.service.ClockService
import anseliq.common.model.model.ID
import anseliq.domain.model
import anseliq.domain.model.FundFlowConfig
import anseliq.domain.port.driven.{FundFlowConfigRepo, Txn}
import com.augustnagro.magnum.{DbCon, Repo, sql}

import java.util.UUID

class FundFlowConfigRepoLive(clock: ClockService) extends AuditedRepo[FundFlowConfig, UUID](clock) with FundFlowConfigRepo:
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
