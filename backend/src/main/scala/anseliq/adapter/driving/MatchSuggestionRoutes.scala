package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.domain.service.MatchSuggestionService
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

// Reviewing secondary-match suggestions (see MatchSuggestionService) from the manual match page.
class MatchSuggestionRoutes(
  transactor:             Transactor,
  matchSuggestionService: MatchSuggestionService
) extends ApiRoutes:

  @cask.route(s"${HttpPath.reconSpec}/:id/match-suggestions", methods = HttpCmd.GET)
  def pendingSuggestions(id: String): String =
    transact(transactor):
      write(matchSuggestionService.pendingForSpec(UUID.fromString(id)).map(PayloadMapper.matchSuggestionFromDomain))

  @cask.route(s"${HttpPath.matchSuggestion}/:id/accept", methods = HttpCmd.POST)
  def acceptSuggestion(id: String): String =
    transact(transactor):
      write(PayloadMapper.reconFromDomain(matchSuggestionService.accept(UUID.fromString(id))))

  @cask.route(s"${HttpPath.matchSuggestion}/:id/reject", methods = HttpCmd.POST)
  def rejectSuggestion(id: String): String =
    transact(transactor):
      matchSuggestionService.reject(UUID.fromString(id))
      write("rejected")

  initialize()
