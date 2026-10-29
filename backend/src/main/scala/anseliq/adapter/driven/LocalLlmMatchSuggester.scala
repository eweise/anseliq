package anseliq.adapter.driven

import anseliq.common.model.*
import anseliq.domain.port.driven.{MatchCandidate, MatchExample, MatchRequest, MatchSuggester, ProposedMatch}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging

import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.time.Duration
import scala.util.Try

/**
 * A MatchSuggester backed by a model served on the install's own hardware, through the OpenAI-style
 * chat completions API that Ollama, llama.cpp's server and vLLM all serve. Nothing leaves the
 * network as long as baseUrl points inside it.
 *
 * The candidates go to the model numbered 1..n rather than by id, since a small model copies a
 * short number far more reliably than a UUID, and the server is asked to hold the answer to
 * AnswerSchema. An answer that still doesn't parse, or names no candidate, is treated as no
 * suggestion. A server that's down or erroring throws, so the SuggestMatches job is retried.
 *
 * The answer can also explain the match as steps from a fixed list (not free-form regexes, which a
 * small model writes badly) that turn each side's field into the same value. It becomes a
 * StringAttributeMatchRule, which MatchSuggestionService checks against the movements before
 * keeping it.
 */
class LocalLlmMatchSuggester(baseUrl: String, val model: String, timeout: Duration)
    extends MatchSuggester
    with StrictLogging:
  import LocalLlmMatchSuggester.*

  val promptVersion = PromptVersion

  private val client   = HttpClient.newBuilder().connectTimeout(timeout).build()
  private val endpoint = URI.create(s"${baseUrl.stripSuffix("/")}/chat/completions")

  def suggest(request: MatchRequest): Option[ProposedMatch] =
    val body = ujson.Obj(
      "model"       -> model,
      "temperature" -> 0,
      "messages" -> ujson.Arr(
        ujson.Obj("role" -> "system", "content" -> SystemPrompt),
        ujson.Obj("role" -> "user", "content"   -> userMessage(request))
      ),
      "response_format" -> ujson.Obj(
        "type"        -> "json_schema",
        "json_schema" -> ujson.Obj("name" -> "match", "strict" -> true, "schema" -> AnswerSchema)
      )
    )
    val response = client.send(
      HttpRequest
        .newBuilder(endpoint)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(body.render()))
        .build(),
      BodyHandlers.ofString()
    )
    if response.statusCode() != 200 then
      throw IOException(s"$model at $endpoint returned ${response.statusCode()}: ${response.body().take(500)}")
    val content = ujson.read(response.body())("choices")(0)("message")("content").str
    val answer  = parseAnswer(content, request)
    if answer.isEmpty then logger.debug(s"$model proposed no match: $content")
    answer

object LocalLlmMatchSuggester:
  val PromptVersion = "v2"

  def fromConfig(config: Config): LocalLlmMatchSuggester =
    LocalLlmMatchSuggester(
      baseUrl = config.getString("match-suggestions.local-llm.base-url"),
      model   = config.getString("match-suggestions.local-llm.model"),
      timeout = Duration.ofSeconds(config.getLong("match-suggestions.local-llm.timeout-seconds"))
    )

  val SystemPrompt: String =
    """You help reconcile financial transactions. A movement on one side of a reconciliation was not
      |matched by the reconciliation's rule. Decide which of the numbered candidates from the other
      |side is the same real-world transaction, if any.
      |
      |Every candidate already has the same amount as the movement, so the amount doesn't tell them
      |apart. Compare the fields the rule compares, and the other fields, allowing for formatting
      |differences such as case, punctuation, spacing, prefixes and leading zeros. Past matches and
      |past non-matches, when given, are pairs people decided for this account: follow their pattern.
      |
      |Answer with JSON only. "match" is the number of the candidate that is the same transaction, or
      |null if none clearly is. "confidence" is from 0 to 1. "reason" is one short sentence a reviewer
      |can check against the fields. Prefer null to a guess.
      |
      |"explanation" shows how the two references become equal, when they do: the movement's field
      |and the candidate's field, and for each the steps that turn its value into the shared value,
      |in order. The steps are "normalize" (lowercase, keep only letters and digits), "strip_prefix"
      |(remove "text" from the start) and "trim_leading_zeros"; "text" is null for the others. Use
      |null when "match" is null, or when no such steps make the two values exactly equal. The
      |explanation is checked against the fields, so only give one that holds exactly.""".stripMargin

  private val StepSchema = ujson.Obj(
    "type" -> "object",
    "properties" -> ujson.Obj(
      "op"   -> ujson.Obj("type" -> "string", "enum" -> ujson.Arr("normalize", "strip_prefix", "trim_leading_zeros")),
      "text" -> ujson.Obj("type" -> ujson.Arr("string", "null"))
    ),
    "required"             -> ujson.Arr("op", "text"),
    "additionalProperties" -> false
  )

  private val ExplanationSchema = ujson.Obj(
    "type" -> ujson.Arr("object", "null"),
    "properties" -> ujson.Obj(
      "movement_field"  -> ujson.Obj("type" -> "string"),
      "movement_steps"  -> ujson.Obj("type" -> "array", "items" -> StepSchema),
      "candidate_field" -> ujson.Obj("type" -> "string"),
      "candidate_steps" -> ujson.Obj("type" -> "array", "items" -> StepSchema)
    ),
    "required"             -> ujson.Arr("movement_field", "movement_steps", "candidate_field", "candidate_steps"),
    "additionalProperties" -> false
  )

  val AnswerSchema: ujson.Obj = ujson.Obj(
    "type" -> "object",
    "properties" -> ujson.Obj(
      "match"       -> ujson.Obj("type" -> ujson.Arr("integer", "null")),
      "confidence"  -> ujson.Obj("type" -> "number"),
      "reason"      -> ujson.Obj("type" -> "string"),
      "explanation" -> ExplanationSchema
    ),
    "required"             -> ujson.Arr("match", "confidence", "reason", "explanation"),
    "additionalProperties" -> false
  )

  private def describe(c: MatchCandidate): ujson.Obj =
    ujson.Obj(
      "received" -> c.createdAt.toLocalDate.toString,
      "amount"   -> c.amount.toString,
      "fields"   -> ujson.Obj.from(c.properties.toSeq.sortBy(_._1).map((k, v) => k -> ujson.Str(v)))
    )

  private def describe(e: MatchExample): ujson.Obj =
    ujson.Obj("movement" -> describe(e.movement), "matched_with" -> ujson.Arr.from(e.matchedWith.map(describe)))

  def userMessage(request: MatchRequest): String =
    val context = request.context
    val message = ujson.Obj(
      "rule_compares" -> context.ruleCondition,
      "movement"      -> describe(request.movement),
      "candidates" -> ujson.Arr.from(request.candidates.zipWithIndex.map { (c, i) =>
        val numbered = describe(c)
        numbered("number") = i + 1
        numbered
      })
    )
    if context.pastMatches.nonEmpty then message("past_matches") = ujson.Arr.from(context.pastMatches.map(describe))
    if context.pastNonMatches.nonEmpty then
      message("past_non_matches") = ujson.Arr.from(context.pastNonMatches.map(describe))
    message.render(indent         = 2)

  /**
   * The model's answer as a proposal, or None for null, an out-of-range number, or unparseable
   * JSON.
   */
  def parseAnswer(content: String, request: MatchRequest): Option[ProposedMatch] =
    // Some models wrap JSON in a ```json fence even when asked not to.
    val json = content.trim.stripPrefix("```json").stripPrefix("```").stripSuffix("```").trim
    for
      answer     <- Try(ujson.read(json).obj).toOption
      number     <- answer.get("match").flatMap(m => Try(m.num.toInt).toOption)
      candidate  <- request.candidates.lift(number - 1)
      confidence <- answer.get("confidence").flatMap(c => Try(BigDecimal(c.num)).toOption)
    yield ProposedMatch(
      movementIds = Seq(candidate.movementId),
      confidence  = confidence,
      rationale   = answer.get("reason").flatMap(r => Try(r.str).toOption).getOrElse(""),
      explanation = answer.get("explanation").flatMap(parseExplanation)
    )

  /**
   * The explanation as a rule (the movement's field on the in side, the candidate's on the out
   * side), or None if it's null or malformed. Not checked against the movements here.
   */
  def parseExplanation(explanation: ujson.Value): Option[StringAttributeMatchRule] =
    def steps(value: ujson.Value): Option[List[StringModifier]] =
      Try(value.arr.toList).toOption.flatMap: list =>
        val parsed = list.map: step =>
          Try(step("op").str).toOption match
            case Some("normalize")          => Some(Normalize)
            case Some("trim_leading_zeros") => Some(TrimLeadingZeros)
            case Some("strip_prefix") =>
              step.obj.get("text").flatMap(_.strOpt).filter(_.nonEmpty).map(StripPrefix(_))
            case _ => None
        Option.when(parsed.forall(_.isDefined))(parsed.flatten)
    def modifier(list: List[StringModifier]): Option[StringModifier] = list match
      case Nil        => None
      case one :: Nil => Some(one)
      case many       => Some(Chain(many))
    for
      e              <- Option(explanation).filterNot(_.isNull).flatMap(v => Try(v.obj).toOption)
      movementField  <- e.get("movement_field").flatMap(_.strOpt).filter(_.nonEmpty)
      candidateField <- e.get("candidate_field").flatMap(_.strOpt).filter(_.nonEmpty)
      movementSteps  <- e.get("movement_steps").flatMap(steps)
      candidateSteps <- e.get("candidate_steps").flatMap(steps)
    yield StringAttributeMatchRule(
      inKey  = StringAttribute(movementField, modifier(movementSteps)),
      outKey = StringAttribute(candidateField, modifier(candidateSteps))
    )
