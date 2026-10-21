package anseliq.adapter.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.{MatchSuggestion, MatchSuggestionStatus}
import anseliq.domain.port.driven.MatchSuggestionRepo
import anseliq.domain.service.ClockService
import com.augustnagro.magnum.{DbCon, sql}

import java.util.UUID

class MatchSuggestionRepoLive(clock: ClockService)
    extends AuditedRepo[MatchSuggestion, UUID](clock)
    with MatchSuggestionRepo:
  val s = MatchSuggestion.Table

  def insertIfNoPending(suggestion: MatchSuggestion)(using DbCon): Option[MatchSuggestion] =
    sql"""
      INSERT INTO $s ${s.insertColumns}
      VALUES (${stampCreated(suggestion)})
      ON CONFLICT (movement_id) WHERE status = 'pending' DO NOTHING
      RETURNING ${s.all}
    """.query[MatchSuggestion].run().headOption

  def findPendingByReconSpecId(reconSpecId: ID)(using DbCon): Vector[MatchSuggestion] =
    sql"""
      SELECT ${s.all}
      FROM $s
      WHERE recon_spec_id = $reconSpecId AND status = ${MatchSuggestionStatus.Pending}
      ORDER BY created_at
    """.query[MatchSuggestion].run()

  def findRejectedByReconSpecId(reconSpecId: ID)(using DbCon): Vector[MatchSuggestion] =
    sql"""
      SELECT ${s.all}
      FROM $s
      WHERE recon_spec_id = $reconSpecId AND status = ${MatchSuggestionStatus.Rejected}
    """.query[MatchSuggestion].run()

  def findRecentRejected(reconSpecId: ID, limit: Int)(using DbCon): Vector[MatchSuggestion] =
    sql"""
      SELECT ${s.all}
      FROM $s
      WHERE recon_spec_id = $reconSpecId AND status = ${MatchSuggestionStatus.Rejected}
      ORDER BY updated_at DESC
      LIMIT $limit
    """.query[MatchSuggestion].run()
