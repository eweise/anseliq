package anseliq.domain.service

import anseliq.common.model.{
  AppliedRules,
  Normalize,
  StringAttribute,
  StringAttributeMatchRule,
  StringModifier,
  StripPrefix,
  SubstringModifier
}
import anseliq.common.model.model.ID
import anseliq.domain.DataSetup
import anseliq.domain.model.{MatchSuggestionStatus, ReconciliationSpec, UserRole, createId}
import anseliq.domain.port.driven.{MatchCandidate, MatchRequest, MatchSuggester, ProposedMatch, Txn}
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class MatchSuggestionServiceSuite extends munit.FunSuite:

  // Proposes whatever the test sets, and records what it was asked.
  private class FakeSuggester extends MatchSuggester:
    val model         = "fake-model"
    val promptVersion = "v1"
    var answer: MatchRequest => Option[ProposedMatch] = _ => None
    def suggest(request: MatchRequest): Option[ProposedMatch] = answer(request)

  // Each test gets its own container: some move the clock.
  private def withService(test: (TestAppContainer, DataSetup, MatchSuggestionService) => Txn[Unit]): Unit =
    val app = TestAppContainer()
    val service = MatchSuggestionService(
      reconSpecRepo       = app.reconSpecRepo,
      reconRepo           = app.reconRepo,
      movementRepo        = app.movementRepo,
      matchSuggestionRepo = app.matchSuggestionRepo,
      suggester           = FakeSuggester(),
      manualReconciler    = app.manualReconciler,
      transactor          = app.transactor,
      candidateWindowDays = 7,
      maxCandidates       = 10
    )
    transact(app.transactor):
      rollBackOnly:
        test(app, DataSetup(app), service)

  // journalToRecon moves into the Recon account (the spec's in side), reportToRecon out of it.
  private def postIn(app: TestAppContainer, data: DataSetup, amount: BigDecimal, name: String): Txn[Unit] =
    app.accountService.postAmount(data.journalToRecon, amount, createId(), Map("name" -> name))
  private def postOut(app: TestAppContainer, data: DataSetup, amount: BigDecimal, name: String): Txn[Unit] =
    app.accountService.postAmount(data.reportToRecon, amount, createId(), Map("name" -> name))

  private def named(candidates: Seq[MatchCandidate]): Seq[String] = candidates.map(_.properties("name"))

  private def idOf(request: MatchRequest, name: String): ID =
    request.candidates.find(_.properties("name") == name).get.movementId

  test("findRequests offers only same-amount movements on the other side, created within the window"):
    withService: (app, data, service) =>
      postOut(app, data, 100, "too-old")
      app.clock.addHours(24 * 10)
      postIn(app, data, 100, "anchor")
      postIn(app, data, 70, "no-candidates")
      postOut(app, data, 100, "near")
      postOut(app, data, 55, "wrong-amount")

      val requests = service.findRequests(data.reconAccount.id)
      assertEquals(
        requests.map(_._2.movement.properties("name")),
        Vector("anchor"),
        "a movement with no candidates isn't asked about"
      )
      assertEquals(named(requests.head._2.candidates), Seq("near"))

  test("record stores a valid proposal as pending, and the movement isn't asked about again"):
    withService: (app, data, service) =>
      postIn(app, data, 100, "anchor")
      postOut(app, data, 100, "match")
      val (spec, request) = service.findRequests(data.reconAccount.id).head

      val stored = service
        .record(spec, request, ProposedMatch(Seq(idOf(request, "match")), BigDecimal("0.8765"), "same reference"))
        .get
      assertEquals(stored.status, MatchSuggestionStatus.Pending)
      assertEquals(stored.suggestedMovementIds, Seq(idOf(request, "match")))
      assertEquals(stored.confidence, BigDecimal("0.877"))
      assertEquals((stored.model, stored.promptVersion), ("fake-model", "v1"))

      assertEquals(service.findRequests(data.reconAccount.id), Vector.empty)
      assertEquals(
        service.record(spec, request, ProposedMatch(Seq(idOf(request, "match")), BigDecimal("0.9"), "again")),
        None,
        "one pending suggestion per movement"
      )

  test("record discards a proposal that doesn't hold up"):
    withService: (app, data, service) =>
      postIn(app, data, 100, "anchor")
      postOut(app, data, 100, "a")
      postOut(app, data, 100, "b")
      postOut(app, data, 30, "outside")
      val (spec, request) = service.findRequests(data.reconAccount.id).head
      val outsideId = app.movementRepo
        .findUnreconciledByAccount(data.reconAccount.id)
        .find(_.props.get("name").contains("outside"))
        .get
        .id

      val bad = Seq(
        "not a candidate"      -> ProposedMatch(Seq(outsideId), BigDecimal("0.9"), "?"),
        "two movements"        -> ProposedMatch(Seq(idOf(request, "a"), idOf(request, "b")), BigDecimal("0.9"), "?"),
        "confidence above one" -> ProposedMatch(Seq(idOf(request, "a")), BigDecimal("1.5"), "?"),
        "no movement"          -> ProposedMatch(Seq.empty, BigDecimal("0.9"), "?")
      )
      bad.foreach: (why, proposal) =>
        assertEquals(service.record(spec, request, proposal), None, why)
      assertEquals(app.matchSuggestionRepo.findPendingByReconSpecId(spec.id), Vector.empty)

  private def newReviewer(app: TestAppContainer): Txn[ID] =
    app.userService
      .createUser(s"reviewer-${createId()}@example.com", "Rita", "Reviewer", UserRole.Analyst, "a-real-password")
      .id

  // Two movements on the in side, both offered the same out-side movement, each with a pending suggestion.
  private def twoSuggestionsForOneMovement(
    app:     TestAppContainer,
    data:    DataSetup,
    service: MatchSuggestionService
  ): Txn[Vector[anseliq.domain.model.MatchSuggestion]] =
    postIn(app, data, 100, "first")
    postIn(app, data, 100, "second")
    postOut(app, data, 100, "shared")
    service
      .findRequests(data.reconAccount.id)
      .map: (spec, request) =>
        service
          .record(spec, request, ProposedMatch(Seq(idOf(request, "shared")), BigDecimal("0.9"), "same reference"))
          .get

  test("accept reconciles the suggested movements and marks the suggestion Accepted"):
    withService: (app, data, service) =>
      val suggestion = twoSuggestionsForOneMovement(app, data, service).head
      val reviewer   = newReviewer(app)
      val recon      = service.accept(suggestion.id, reviewer)

      assertEquals(recon.status, anseliq.domain.model.ReconciliationStatus.Final)
      assertEquals(
        recon.appliedRules,
        AppliedRules.Suggested(suggestion.id.toString, "fake-model", "v1", BigDecimal("0.900"), "same reference"),
        "the reconciliation records which model proposed it, not just Manual"
      )
      val matched = (suggestion.movementId +: suggestion.suggestedMovementIds).flatMap(app.movementRepo.findById)
      assert(matched.forall(_.reconciliationId.contains(recon.id)), "both movements are in the new reconciliation")
      val accepted = app.matchSuggestionRepo.findById(suggestion.id).get
      assertEquals((accepted.status, accepted.reviewedByUserId), (MatchSuggestionStatus.Accepted, Some(reviewer)))
      intercept[anseliq.domain.model.IllegalStateError](service.accept(suggestion.id, reviewer))

  test("reject marks the suggestion Rejected and reconciles nothing"):
    withService: (app, data, service) =>
      val suggestion = twoSuggestionsForOneMovement(app, data, service).head
      val reviewer   = newReviewer(app)
      service.reject(suggestion.id, reviewer)

      val rejected = app.matchSuggestionRepo.findById(suggestion.id).get
      assertEquals((rejected.status, rejected.reviewedByUserId), (MatchSuggestionStatus.Rejected, Some(reviewer)))
      assert(app.movementRepo.findById(suggestion.movementId).get.reconciliationId.isEmpty)
      assertEquals(service.pendingForSpec(data.reconSpec.id).map(_.id).toSet.contains(suggestion.id), false)

  test("pendingForSpec leaves out a suggestion whose movement has since been matched"):
    withService: (app, data, service) =>
      val suggestions = twoSuggestionsForOneMovement(app, data, service)
      assertEquals(service.pendingForSpec(data.reconSpec.id).size, 2)

      service.accept(suggestions.head.id, newReviewer(app))
      assertEquals(
        service.pendingForSpec(data.reconSpec.id),
        Vector.empty,
        "the other one's out-side movement is now reconciled"
      )

  private def unreconciledId(app: TestAppContainer, data: DataSetup, name: String): Txn[ID] =
    app.movementRepo.findUnreconciledByAccount(data.reconAccount.id).find(_.props.get("name").contains(name)).get.id

  test("a rejected pair isn't offered again, but the movement's other candidates still are"):
    withService: (app, data, service) =>
      postIn(app, data, 100, "anchor")
      postOut(app, data, 100, "wrong")
      postOut(app, data, 100, "right")
      val (spec, request) = service.findRequests(data.reconAccount.id).head
      val rejected =
        service.record(spec, request, ProposedMatch(Seq(idOf(request, "wrong")), BigDecimal("0.6"), "?")).get
      service.reject(rejected.id, newReviewer(app))

      val requests = service.findRequests(data.reconAccount.id)
      assertEquals(requests.flatMap((_, r) => named(r.candidates)), Vector("right"))

  test("each request carries the rule in words, recent hand-made matches, and rejected suggestions"):
    withService: (app, data, service) =>
      postIn(app, data, 10, "matched-in")
      postOut(app, data, 10, "matched-out")
      app.manualReconciler.matchMovements(
        data.reconSpec.id,
        Seq(unreconciledId(app, data, "matched-in")),
        Seq(unreconciledId(app, data, "matched-out")),
        "Manual match"
      )

      postIn(app, data, 20, "rejected-in")
      postOut(app, data, 20, "rejected-out")
      val (spec, request) = service.findRequests(data.reconAccount.id).head
      val rejected =
        service.record(spec, request, ProposedMatch(Seq(idOf(request, "rejected-out")), BigDecimal("0.6"), "?")).get
      service.reject(rejected.id, newReviewer(app))

      postIn(app, data, 30, "anchor")
      postOut(app, data, 30, "candidate")
      val context =
        service.findRequests(data.reconAccount.id).map(_._2).find(_.movement.properties("name") == "anchor").get.context

      assertEquals(context.ruleCondition, "id = id", "DataSetup's rule compares id with id")
      assertEquals(
        context.pastMatches.map(e => e.movement.properties("name") -> named(e.matchedWith)),
        Seq("matched-in" -> Seq("matched-out"))
      )
      assertEquals(
        context.pastNonMatches.map(e => e.movement.properties("name") -> named(e.matchedWith)),
        Seq("rejected-in" -> Seq("rejected-out"))
      )

  test("an accepted suggestion becomes a past-match example, like a hand-made match"):
    withService: (app, data, service) =>
      val accepted = twoSuggestionsForOneMovement(app, data, service).head
      service.accept(accepted.id, newReviewer(app))

      postIn(app, data, 30, "anchor")
      postOut(app, data, 30, "candidate")
      val context =
        service.findRequests(data.reconAccount.id).map(_._2).find(_.movement.properties("name") == "anchor").get.context
      assertEquals(context.pastMatches.map(e => named(e.matchedWith)), Seq(Seq("shared")))

  // An invoice on the in side and the bank's differently formatted copy of its reference on the out side.
  private def invoicePair(app: TestAppContainer, data: DataSetup): Txn[(ReconciliationSpec, MatchRequest)] =
    app.accountService.postAmount(
      data.journalToRecon,
      1250,
      createId(),
      Map("name" -> "anchor", "ref" -> "INV-2026-0042")
    )
    app.accountService.postAmount(data.reportToRecon, 1250, createId(), Map("name" -> "bank", "ref" -> "inv20260042"))
    service(app).findRequests(data.reconAccount.id).head

  private def service(app: TestAppContainer) = MatchSuggestionService(
    reconSpecRepo       = app.reconSpecRepo,
    reconRepo           = app.reconRepo,
    movementRepo        = app.movementRepo,
    matchSuggestionRepo = app.matchSuggestionRepo,
    suggester           = FakeSuggester(),
    manualReconciler    = app.manualReconciler,
    transactor          = app.transactor,
    candidateWindowDays = 7,
    maxCandidates       = 10
  )

  private def refRule(in: Option[StringModifier], out: Option[StringModifier]) =
    StringAttributeMatchRule(StringAttribute("ref", in), StringAttribute("ref", out))

  test("a checked explanation is stored with the suggestion and carried into the reconciliation"):
    withService: (app, data, service) =>
      val (spec, request) = invoicePair(app, data)
      val proposal = ProposedMatch(
        Seq(idOf(request, "bank")),
        BigDecimal("0.9"),
        "same invoice",
        Some(refRule(Some(Normalize), Some(Normalize)))
      )
      val stored = service.record(spec, request, proposal).get
      assertEquals(stored.explanation.map(_.value), Some("inv20260042"))
      assertEquals(
        service.pendingForSpec(spec.id).head.explanation.map(_.summary),
        Some("normalize(ref) = normalize(ref): inv20260042")
      )

      val recon = service.accept(stored.id, newReviewer(app))
      assertEquals(
        recon.appliedRules.asInstanceOf[AppliedRules.Suggested].explanation.map(_.value),
        Some("inv20260042")
      )

  test("an explanation that doesn't hold is dropped, but the suggestion is still stored"):
    withService: (app, data, service) =>
      val (spec, request) = invoicePair(app, data)
      val proposal =
        ProposedMatch(Seq(idOf(request, "bank")), BigDecimal("0.9"), "same invoice", Some(refRule(None, None)))
      val stored = service.record(spec, request, proposal).get
      assertEquals(stored.explanation, None, "'INV-2026-0042' and 'inv20260042' aren't equal without normalizing")

  test("checkExplanation rejects constants, missing fields and empty values"):
    withService: (app, data, service) =>
      val (_, request) = invoicePair(app, data)
      val bank         = request.candidates.head
      def check(rule: StringAttributeMatchRule) = service.checkExplanation(rule, request.movement, bank)
      assertEquals(check(refRule(Some(Normalize), Some(Normalize))).map(_.value), Some("inv20260042"))
      assertEquals(
        check(
          StringAttributeMatchRule(
            StringAttribute("inv20260042", isConstant = true),
            StringAttribute("ref", Some(Normalize))
          )
        ),
        None,
        "constant"
      )
      assertEquals(
        check(StringAttributeMatchRule(StringAttribute("missing"), StringAttribute("ref"))),
        None,
        "missing field"
      )
      assertEquals(
        check(refRule(Some(SubstringModifier("[0-9]{20}")), Some(SubstringModifier("[0-9]{20}")))),
        None,
        "different values"
      )
      assertEquals(
        check(
          StringAttributeMatchRule(
            StringAttribute("name", Some(StripPrefix("anchor"))),
            StringAttribute("name", Some(StripPrefix("bank")))
          )
        ),
        None,
        "both reduce to empty"
      )

  // `count` invoices accepted as suggestions, each explained by normalize(ref) = normalize(ref).
  private def acceptExplainedSuggestions(
    app:     TestAppContainer,
    data:    DataSetup,
    service: MatchSuggestionService,
    count:   Int
  ): Txn[Unit] =
    val reviewer = newReviewer(app)
    (1 to count).foreach: i =>
      app.accountService.postAmount(
        data.journalToRecon,
        1000 + i,
        createId(),
        Map("name" -> s"in-$i", "ref" -> s"INV-00$i")
      )
      app.accountService.postAmount(
        data.reportToRecon,
        1000 + i,
        createId(),
        Map("name" -> s"out-$i", "ref" -> s"inv00$i")
      )
    service
      .findRequests(data.reconAccount.id)
      .foreach: (spec, request) =>
        val proposal = ProposedMatch(
          Seq(request.candidates.head.movementId),
          BigDecimal("0.9"),
          "same invoice",
          Some(refRule(Some(Normalize), Some(Normalize)))
        )
        service.accept(service.record(spec, request, proposal).get.id, reviewer)

  test("ruleProposals offers a rule once it has explained enough accepted suggestions"):
    withService: (app, data, service) =>
      acceptExplainedSuggestions(app, data, service, 3)
      // Still unmatched: one pair the learned rule would match, and a movement it wouldn't.
      app.accountService.postAmount(data.journalToRecon, 77, createId(), Map("name" -> "later-in", "ref" -> "AB-9"))
      app.accountService.postAmount(data.reportToRecon, 77, createId(), Map("name" -> "later-out", "ref" -> "ab9"))
      app.accountService.postAmount(data.journalToRecon, 55, createId(), Map("name" -> "alone", "ref" -> "ZZ-1"))

      assertEquals(
        service.ruleProposals(data.reconSpec.id),
        Vector(RuleProposal(refRule(Some(Normalize), Some(Normalize)), acceptedSuggestions = 3, unmatchedPairs = 1))
      )

  test("ruleProposals leaves out a rule with too few examples, or one the spec already has"):
    withService: (app, data, service) =>
      acceptExplainedSuggestions(app, data, service, 2)
      assertEquals(service.ruleProposals(data.reconSpec.id), Vector.empty, "two accepted suggestions isn't enough")

      acceptExplainedSuggestions(app, data, service, 1)
      val withLearnedRule = data.reconSpec.copy(ruleJson =
        upickle.write[anseliq.common.model.Rule](
          anseliq.common.model.ORRule(List(data.reconSpec.rule(), refRule(Some(Normalize), Some(Normalize))))
        )
      )
      app.reconSpecRepo.update(withLearnedRule)
      assertEquals(service.ruleProposals(data.reconSpec.id), Vector.empty, "the spec already matches on it")
