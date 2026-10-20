package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.MatchSuggestion
import com.augustnagro.magnum.DbCon

trait MatchSuggestionRepo extends DomainRepo[MatchSuggestion, MatchSuggestion]:
  /**
   * Inserts unless the movement already has a pending suggestion (see the
   * match_suggestion_pending_unique index). Returns None on conflict.
   */
  def insertIfNoPending(suggestion: MatchSuggestion)(using DbCon): Option[MatchSuggestion]

  def findPendingByReconSpecId(reconSpecId: ID)(using DbCon): Vector[MatchSuggestion]
