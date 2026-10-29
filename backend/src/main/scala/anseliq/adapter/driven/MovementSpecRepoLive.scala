package anseliq.adapter.driven

import anseliq.domain.service.ClockService
import anseliq.common.model.model.ID
import anseliq.domain.model.MovementSpec
import anseliq.domain.port.driven.{MovementSpecRepo, Txn}
import com.augustnagro.magnum.{DbCon, Repo, sql}

import java.util.UUID

class MovementSpecRepoLive(clock: ClockService) extends AuditedRepo[MovementSpec, UUID](clock) with MovementSpecRepo:
  val ms = MovementSpec.Table

  def findByLedgerId(ledgerId: ID): Txn[Vector[MovementSpec]] =
    sql"""
      SELECT ms.*
      FROM $ms AS ms
      JOIN account a ON ms.in_account_id = a.id
      WHERE a.ledger_id = $ledgerId
    """.query[MovementSpec].run()

  def findByFileFeedId(fileFeedId: ID): Txn[Vector[MovementSpec]] =
    sql"""
      SELECT ${ms.all}
      FROM $ms
      WHERE file_feed_id = $fileFeedId
    """.query[MovementSpec].run()
