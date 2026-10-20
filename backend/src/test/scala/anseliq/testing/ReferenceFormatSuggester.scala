package anseliq.testing

import anseliq.domain.port.driven.{MatchCandidate, MatchRequest, MatchSuggester, ProposedMatch}

// Stands in for a local model in HappyPathScenario: proposes the first candidate sharing a reference
// with the movement once case and punctuation are ignored ("INV-2026-0042" and "inv20260042"),
// the kind of near-miss an exact-match rule leaves behind.
class ReferenceFormatSuggester extends MatchSuggester:
  val model         = "reference-format-demo"
  val promptVersion = "n/a"

  private def references(c: MatchCandidate): Map[String, String] =
    c.properties.removed("amount").filter((_, v) => normalize(v).nonEmpty)

  private def normalize(value: String): String = value.toLowerCase.filter(_.isLetterOrDigit)

  def suggest(request: MatchRequest): Option[ProposedMatch] =
    val own = references(request.movement)
    request.candidates.iterator
      .flatMap: candidate =>
        references(candidate).values.collectFirst:
          case theirs if own.values.exists(normalize(_) == normalize(theirs)) =>
            val mine = own.values.find(normalize(_) == normalize(theirs)).get
            ProposedMatch(
              movementIds = Seq(candidate.movementId),
              confidence  = BigDecimal("0.92"),
              rationale   = s"Same reference once formatting is ignored: $mine and $theirs"
            )
      .nextOption()
