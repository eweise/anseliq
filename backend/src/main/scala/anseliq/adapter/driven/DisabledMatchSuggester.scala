package anseliq.adapter.driven

import anseliq.domain.port.driven.{MatchRequest, MatchSuggester, ProposedMatch}

// The MatchSuggester until a local-model adapter exists: never proposes anything.
class DisabledMatchSuggester extends MatchSuggester:
  val model         = "none"
  val promptVersion = "none"

  def suggest(request: MatchRequest): Option[ProposedMatch] = None
