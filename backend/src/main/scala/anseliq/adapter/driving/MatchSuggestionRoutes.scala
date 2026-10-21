package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.{RuleMatch, RuleProposalPayload}
import anseliq.common.util.JwtHelper
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

  @cask.route(s"${HttpPath.reconSpec}/:id/rule-proposals", methods = HttpCmd.GET)
  def ruleProposals(id: String): String =
    transact(transactor):
      write(
        matchSuggestionService
          .ruleProposals(UUID.fromString(id))
          .map(p => RuleProposalPayload(p.rule, RuleMatch(p.rule, "").condition, p.acceptedSuggestions, p.unmatchedPairs))
      )

  @cask.route(s"${HttpPath.matchSuggestion}/:id/accept", methods = HttpCmd.POST)
  def acceptSuggestion(id: String)(currentUser: JwtHelper.UserClaim): String =
    transact(transactor):
      val recon = matchSuggestionService.accept(UUID.fromString(id), UUID.fromString(currentUser.userId))
      write(PayloadMapper.reconFromDomain(recon))

  @cask.route(s"${HttpPath.matchSuggestion}/:id/reject", methods = HttpCmd.POST)
  def rejectSuggestion(id: String)(currentUser: JwtHelper.UserClaim): String =
    transact(transactor):
      matchSuggestionService.reject(UUID.fromString(id), UUID.fromString(currentUser.userId))
      write("rejected")

  initialize()
