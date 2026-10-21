package anseliq.adapter.driven

import anseliq.domain.model.createID
import anseliq.domain.port.driven.{MatchCandidate, MatchContext, MatchExample, MatchRequest}
import com.sun.net.httpserver.HttpServer

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, OffsetDateTime}

// Runs against a stub chat-completions server, so no model is needed.
class LocalLlmMatchSuggesterSuite extends munit.FunSuite:

  private def candidate(ref: String) =
    MatchCandidate(createID(), BigDecimal("1250.00"), OffsetDateTime.parse("2026-10-20T09:00:00Z"), Map("txn_id" -> ref))

  private val movement = MatchCandidate(
    createID(),
    BigDecimal("1250.00"),
    OffsetDateTime.parse("2026-10-20T09:00:00Z"),
    Map("transaction_id" -> "INV-2026-0042")
  )
  private val candidates = Seq(candidate("inv20260099"), candidate("inv20260042"))
  private val context = MatchContext(
    ruleCondition  = "transaction_id = txn_id",
    pastMatches    = Seq(MatchExample(movement.copy(properties = Map("transaction_id" -> "INV-2026-0007")), Seq(candidate("inv20260007")))),
    pastNonMatches = Seq.empty
  )
  private val request = MatchRequest(movement, candidates, context)

  // Serves `reply` (status, chat content) for every request, recording each request body.
  private def withServer(status: Int, content: String)(test: (LocalLlmMatchSuggester, () => ujson.Value) => Unit): Unit =
    var lastBody = ""
    val server   = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/chat/completions",
      exchange =>
        lastBody = String(exchange.getRequestBody.readAllBytes(), UTF_8)
        val reply = ujson.Obj("choices" -> ujson.Arr(ujson.Obj("message" -> ujson.Obj("role" -> "assistant", "content" -> content))))
        val bytes = reply.render().getBytes(UTF_8)
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()
    try
      val suggester = LocalLlmMatchSuggester(s"http://localhost:${server.getAddress.getPort}/v1", "qwen2.5:7b", Duration.ofSeconds(5))
      test(suggester, () => ujson.read(lastBody))
    finally server.stop(0)

  test("sends the movement, numbered candidates and context, and asks for the answer schema"):
    withServer(200, """{"match": null, "confidence": 0, "reason": "none"}"""): (suggester, lastRequest) =>
      suggester.suggest(request)
      val body = lastRequest()
      assertEquals(body("model").str, "qwen2.5:7b")
      assertEquals(body("temperature").num, 0.0)
      assertEquals(body("response_format")("json_schema")("schema"), LocalLlmMatchSuggester.AnswerSchema)

      val message = ujson.read(body("messages")(1)("content").str)
      assertEquals(message("rule_compares").str, "transaction_id = txn_id")
      assertEquals(message("movement")("fields")("transaction_id").str, "INV-2026-0042")
      assertEquals(message("candidates").arr.map(c => c("number").num.toInt -> c("fields")("txn_id").str).toSeq, Seq(1 -> "inv20260099", 2 -> "inv20260042"))
      assertEquals(message("past_matches")(0)("matched_with")(0)("fields")("txn_id").str, "inv20260007")
      assert(!message.obj.contains("past_non_matches"), "empty example lists are left out")

  test("maps the answer's number back to that candidate's movement"):
    withServer(200, """{"match": 2, "confidence": 0.93, "reason": "Same invoice number once formatting is ignored"}"""): (suggester, _) =>
      val proposal = suggester.suggest(request).get
      assertEquals(proposal.movementIds, Seq(candidates(1).movementId))
      assertEquals(proposal.confidence, BigDecimal("0.93"))
      assertEquals(proposal.rationale, "Same invoice number once formatting is ignored")

  test("a server error throws, so the job is retried"):
    withServer(500, "model not loaded"): (suggester, _) =>
      intercept[IOException](suggester.suggest(request))

  test("parseAnswer treats no match, a number outside the candidates, and malformed JSON as no suggestion"):
    val noSuggestion = Seq(
      """{"match": null, "confidence": 0.2, "reason": "none match"}""",
      """{"match": 3, "confidence": 0.9, "reason": "?"}""",
      """{"match": 0, "confidence": 0.9, "reason": "?"}""",
      "I think it's the second one",
      """{"match": 2}"""
    )
    noSuggestion.foreach(content => assertEquals(LocalLlmMatchSuggester.parseAnswer(content, request), None, content))

  test("parseAnswer accepts JSON wrapped in a code fence"):
    val fenced = "```json\n{\"match\": 1, \"confidence\": 0.8, \"reason\": \"r\"}\n```"
    assertEquals(LocalLlmMatchSuggester.parseAnswer(fenced, request).map(_.movementIds), Some(Seq(candidates.head.movementId)))

  private def explanation(movementSteps: String, candidateSteps: String) = ujson.read(
    s"""{"movement_field": "transaction_id", "movement_steps": $movementSteps, "candidate_field": "txn_id", "candidate_steps": $candidateSteps}"""
  )

  test("parseExplanation turns the steps into a rule, chaining several"):
    import anseliq.common.model.*
    val rule = LocalLlmMatchSuggester.parseExplanation(
      explanation("""[{"op": "strip_prefix", "text": "INV-"}, {"op": "normalize", "text": null}]""", """[{"op": "normalize", "text": null}]""")
    )
    assertEquals(
      rule,
      Some(
        StringAttributeMatchRule(
          StringAttribute("transaction_id", Some(Chain(List(StripPrefix("INV-"), Normalize)))),
          StringAttribute("txn_id", Some(Normalize))
        )
      )
    )
    assertEquals(
      LocalLlmMatchSuggester.parseExplanation(explanation("[]", "[]")),
      Some(StringAttributeMatchRule(StringAttribute("transaction_id"), StringAttribute("txn_id"))),
      "no steps compares the fields as they are"
    )

  test("parseExplanation drops a malformed explanation"):
    val malformed = Seq(
      ujson.Null,
      explanation("""[{"op": "uppercase", "text": null}]""", "[]"),
      explanation("""[{"op": "strip_prefix", "text": null}]""", "[]"),
      ujson.read("""{"movement_field": "", "movement_steps": [], "candidate_field": "txn_id", "candidate_steps": []}""")
    )
    malformed.foreach(e => assertEquals(LocalLlmMatchSuggester.parseExplanation(e), None, e.render()))

  test("an answer keeps its match when its explanation is malformed"):
    val content = """{"match": 2, "confidence": 0.9, "reason": "r", "explanation": {"movement_field": "transaction_id", "movement_steps": [{"op": "shout"}], "candidate_field": "txn_id", "candidate_steps": []}}"""
    val proposal = LocalLlmMatchSuggester.parseAnswer(content, request).get
    assertEquals((proposal.movementIds, proposal.explanation), (Seq(candidates(1).movementId), None))
