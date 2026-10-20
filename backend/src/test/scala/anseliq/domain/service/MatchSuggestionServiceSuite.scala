package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.DataSetup
import anseliq.domain.model.{MatchSuggestionStatus, createID}
import anseliq.domain.port.driven.{MatchCandidate, MatchRequest, MatchSuggester, ProposedMatch, Txn}
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact

class MatchSuggestionServiceSuite extends munit.FunSuite:

  // Proposes whatever the test sets, and records what it was asked.
  private class FakeSuggester extends MatchSuggester:
    val model                                  = "fake-model"
    val promptVersion                          = "v1"
    var answer: MatchRequest => Option[ProposedMatch] = _ => None
    def suggest(request: MatchRequest): Option[ProposedMatch] = answer(request)

  // Each test gets its own container: some move the clock.
  private def withService(test: (TestAppContainer, DataSetup, MatchSuggestionService) => Txn[Unit]): Unit =
    val app = TestAppContainer()
    val service = MatchSuggestionService(
      reconSpecRepo       = app.reconSpecRepo,
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
    app.accountService.postAmount(data.journalToRecon, amount, createID(), Map("name" -> name))
  private def postOut(app: TestAppContainer, data: DataSetup, amount: BigDecimal, name: String): Txn[Unit] =
    app.accountService.postAmount(data.reportToRecon, amount, createID(), Map("name" -> name))

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
      assertEquals(requests.map(_._2.movement.properties("name")), Vector("anchor"), "a movement with no candidates isn't asked about")
      assertEquals(named(requests.head._2.candidates), Seq("near"))

  test("record stores a valid proposal as pending, and the movement isn't asked about again"):
    withService: (app, data, service) =>
      postIn(app, data, 100, "anchor")
      postOut(app, data, 100, "match")
      val (spec, request) = service.findRequests(data.reconAccount.id).head

      val stored = service.record(spec, request, ProposedMatch(Seq(idOf(request, "match")), BigDecimal("0.8765"), "same reference")).get
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
        .findUnReconciledByAccount(data.reconAccount.id)
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

  // Two movements on the in side, both offered the same out-side movement, each with a pending suggestion.
  private def twoSuggestionsForOneMovement(
    app:     TestAppContainer,
    data:    DataSetup,
    service: MatchSuggestionService
  ): Txn[Vector[anseliq.domain.model.MatchSuggestion]] =
    postIn(app, data, 100, "first")
    postIn(app, data, 100, "second")
    postOut(app, data, 100, "shared")
    service.findRequests(data.reconAccount.id).map: (spec, request) =>
      service.record(spec, request, ProposedMatch(Seq(idOf(request, "shared")), BigDecimal("0.9"), "same reference")).get

  test("accept reconciles the suggested movements and marks the suggestion Accepted"):
    withService: (app, data, service) =>
      val suggestion = twoSuggestionsForOneMovement(app, data, service).head
      val recon      = service.accept(suggestion.id)

      assertEquals(recon.status, anseliq.domain.model.ReconciliationStatus.Final)
      val matched = (suggestion.movementId +: suggestion.suggestedMovementIds).flatMap(app.movementRepo.findById)
      assert(matched.forall(_.reconciliationId.contains(recon.id)), "both movements are in the new reconciliation")
      assertEquals(app.matchSuggestionRepo.findById(suggestion.id).get.status, MatchSuggestionStatus.Accepted)
      intercept[anseliq.domain.model.IllegalStateError](service.accept(suggestion.id))

  test("reject marks the suggestion Rejected and reconciles nothing"):
    withService: (app, data, service) =>
      val suggestion = twoSuggestionsForOneMovement(app, data, service).head
      service.reject(suggestion.id)

      assertEquals(app.matchSuggestionRepo.findById(suggestion.id).get.status, MatchSuggestionStatus.Rejected)
      assert(app.movementRepo.findById(suggestion.movementId).get.reconciliationId.isEmpty)
      assertEquals(service.pendingForSpec(data.reconSpec.id).map(_.id).toSet.contains(suggestion.id), false)

  test("pendingForSpec leaves out a suggestion whose movement has since been matched"):
    withService: (app, data, service) =>
      val suggestions = twoSuggestionsForOneMovement(app, data, service)
      assertEquals(service.pendingForSpec(data.reconSpec.id).size, 2)

      service.accept(suggestions.head.id)
      assertEquals(service.pendingForSpec(data.reconSpec.id), Vector.empty, "the other one's out-side movement is now reconciled")
