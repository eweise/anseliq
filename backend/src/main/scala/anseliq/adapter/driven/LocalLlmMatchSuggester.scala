package anseliq.adapter.driven

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
 * A MatchSuggester backed by a model served on the install's own hardware, through the
 * OpenAI-style chat completions API that Ollama, llama.cpp's server and vLLM all serve. Nothing
 * leaves the network as long as baseUrl points inside it.
 *
 * The candidates go to the model numbered 1..n rather than by id, since a small model copies a
 * short number far more reliably than a UUID, and the server is asked to hold the answer to
 * AnswerSchema. An answer that still doesn't parse, or names no candidate, is treated as no
 * suggestion. A server that's down or erroring throws, so the SuggestMatches job is retried.
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
        ujson.Obj("role" -> "user", "content" -> userMessage(request))
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
  val PromptVersion = "v1"

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
      |can check against the fields. Prefer null to a guess.""".stripMargin

  val AnswerSchema: ujson.Obj = ujson.Obj(
    "type" -> "object",
    "properties" -> ujson.Obj(
      "match"      -> ujson.Obj("type" -> ujson.Arr("integer", "null")),
      "confidence" -> ujson.Obj("type" -> "number"),
      "reason"     -> ujson.Obj("type" -> "string")
    ),
    "required"             -> ujson.Arr("match", "confidence", "reason"),
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
    message.render(indent = 2)

  /** The model's answer as a proposal, or None for null, an out-of-range number, or unparseable JSON. */
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
      rationale   = answer.get("reason").flatMap(r => Try(r.str).toOption).getOrElse("")
    )
