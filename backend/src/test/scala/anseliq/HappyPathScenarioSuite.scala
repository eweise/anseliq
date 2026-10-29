package anseliq

import anseliq.common.model.{Normalize, StringAttribute, StringAttributeMatchRule}
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.service.RuleProposal
import anseliq.testing.{HappyPathApp, HappyPathScenario}
import anseliq.testing.HappyPathScenario.*
import com.augustnagro.magnum.transact

// Regression test for the happy path across days: files land on time, everything that can match
// does, and the one transaction that never settles ages through the buckets and goes overdue. The
// scenario commits its data like the real pipeline does, so this tears it down afterwards.
class HappyPathScenarioSuite extends munit.FunSuite:

  // Worked out by hand from HappyPathScenario.filesFor. Movements and aging are the Recon account's:
  // every journal row moves into it and every bank row out of it.
  case class Expected(
    reconciliations:    Int,
    movements:          Int,
    unreconciled:       Set[String],
    aging:              (Int, Int, Int, Int), // 0-3, 4-7, 8-14, 15+ days old
    overdue:            Int
  )

  // A reviewed fuzzy pair's two movements are unreconciled on the day they land, and matched (one
  // more reconciliation) when the reviewer accepts the suggestion the next morning.
  private def reviewedPair(day: Int): Set[String] =
    val (journalRef, bankRef, _) = ReviewedFuzzyPairs(day)
    Set(journalRef, bankRef)

  private val expectedByDay = Vector(
    Expected(3, 8, Set(StuckId, "T0-LATE"), (2, 0, 0, 0), 0),
    Expected(7, 15, Set(StuckId), (1, 0, 0, 0), 0),
    Expected(10, 23, Set(StuckId) ++ reviewedPair(2), (3, 0, 0, 0), 0),
    Expected(14, 30, Set(StuckId, "T3-LATE"), (2, 0, 0, 0), 0),
    Expected(18, 39, Set(StuckId) ++ reviewedPair(4), (2, 1, 0, 0), 0),
    Expected(22, 45, Set(StuckId), (0, 1, 0, 0), 0),
    // The stuck one is now past the 5-day aging limit.
    Expected(25, 54, Set(StuckId, "T6-LATE") ++ reviewedPair(6), (3, 1, 0, 0), 1),
    Expected(30, 61, Set(StuckId), (0, 1, 0, 0), 1),
    Expected(33, 67, Set(StuckId), (0, 0, 1, 0), 1),
    // The pending pairs land and stay unreconciled.
    Expected(36, 77, Set(StuckId) ++ PendingFuzzyPairs.flatMap((j, b, _) => Seq(j, b)), (4, 0, 1, 0), 1)
  )


  test("a ledger reconciles, ages and snapshots correctly day by day"):
    assertEquals(expectedByDay.size, Days)
    val app      = HappyPathApp()
    val scenario = HappyPathScenario(app)
    val s        = scenario.start()
    try
      scenario.runDays(s): day =>
        val expected = expectedByDay(day)
        val clue     = s"day $day"
        transact(app.transactor):
          val recons = app.reconRepo.findByReconSpecID(s.reconSpec.id)
          assertEquals(recons.size, expected.reconciliations, clue)
          assert(recons.forall(_.status == ReconciliationStatus.Final), s"$clue: every match should be Final")

          val unreconciled = app.movementRepo
            .findUnReconciledByAccount(s.recon.id)
            .map(m => m.props.getOrElse("transaction_id", m.props.getOrElse("txn_id", "?")))
            .toSet
          assertEquals(unreconciled, expected.unreconciled, clue)

          val summary   = app.reportingService.summary(Some(s.ledger.id))
          val matchRate = summary.matchRates.find(_.accountId == s.recon.id).get
          assertEquals(matchRate.totalMovementCount, expected.movements, clue)
          assertEquals(matchRate.reconciledMovementCount, expected.movements - expected.unreconciled.size, clue)
          val (a, b, c, d) = expected.aging
          assertEquals(summary.aging.buckets, Seq("0-3" -> a, "4-7" -> b, "8-14" -> c, "15+" -> d), clue)
          assertEquals(summary.aging.overdueCount, expected.overdue, clue)
          assertEquals(summary.exceptions.byStatus, Map.empty[String, Int], s"$clue: no exceptions on the happy path")

          val today    = app.clock.now.toLocalDate
          val snapshot = app.dailySnapshotRepo.findTrend(s.ledger.id, today, today)
          assertEquals(snapshot.size, 1, s"$clue: one snapshot for $today")
          assertEquals(
            (snapshot.head.agingZeroToThree, snapshot.head.agingFourToSeven, snapshot.head.agingEightToFourteen, snapshot.head.agingFifteenPlus),
            expected.aging,
            clue
          )
          assertEquals(snapshot.head.overdueCount, expected.overdue, clue)
          assertEquals(snapshot.head.totalMovementCount, expected.movements, clue)

          // Two files a day, each ingested by its own job, then one reconciliation job and one
          // match-suggestion job.
          val inventoryIds = app.fileInventoryRepo.findAll.filter(fi => Set(s.journalFeed.id, s.bankFeed.id).contains(fi.fileFeedId)).map(_.id)
          val ingestionJobs = inventoryIds.flatMap(app.jobRepo.findByJobTypeAndReferenceId(JobType.Ingestion, _))
          val reconJobs     = app.jobRepo.findByJobTypeAndReferenceId(JobType.Reconciliation, s.recon.id)
          assertEquals(ingestionJobs.map(_.jobStatus).count(_ == JobStatus.Complete), 2 * (day + 1), clue)
          assertEquals(reconJobs.map(_.jobStatus), Vector.fill(day + 1)(JobStatus.Complete), clue)
          val suggestJobs = app.jobRepo.findByJobTypeAndReferenceId(JobType.SuggestMatches, s.recon.id)
          assertEquals(suggestJobs.map(_.jobStatus), Vector.fill(day + 1)(JobStatus.Complete), clue)

      transact(app.transactor):
        val today = app.clock.now.toLocalDate
        assertEquals(
          app.dailySnapshotRepo.findTrend(s.ledger.id, today.minusDays(Days - 1), today).size,
          Days,
          "one snapshot per day"
        )
      // The pending pairs were suggested, the three accepted ones taught a rule, and a reviewer can
      // accept or reject each pending one.
      transact(app.transactor):
        def reference(id: ID) =
          val props = app.movementRepo.findById(id).get.props
          props.getOrElse("transaction_id", props.getOrElse("txn_id", "?"))
        val suggestions = app.matchSuggestionService.pendingForSpec(s.reconSpec.id)
        val proposed    = suggestions.map(sg => reference(sg.movementId) -> sg.suggestedMovementIds.map(reference)).toSet
        assertEquals(proposed, PendingFuzzyPairs.map((j, b, _) => j -> Seq(b)).toSet)

        val learned = StringAttributeMatchRule(
          StringAttribute("transaction_id", Some(Normalize)),
          StringAttribute("txn_id", Some(Normalize))
        )
        assertEquals(
          app.matchSuggestionService.ruleProposals(s.reconSpec.id),
          Vector(RuleProposal(learned, acceptedSuggestions = 3, unmatchedPairs = 2)),
          "learned from the three accepted suggestions; it would match the two pending pairs"
        )

        val reviewer                 = s.reviewer
        val (invoice, purchaseOrder) = suggestions.partition(sg => reference(sg.movementId) == PendingFuzzyPairs.head._1)
        val accepted = app.matchSuggestionService.accept(invoice.head.id, reviewer.id)
        assertEquals(accepted.status, ReconciliationStatus.Final)
        assertEquals(
          accepted.appliedRules.summary,
          "normalize(transaction_id) = normalize(txn_id): inv20260042 (suggested by reference-format-demo, 92%)",
          "the suggester's explanation was checked and recorded"
        )
        app.matchSuggestionService.reject(purchaseOrder.head.id, reviewer.id)
        assertEquals(app.matchSuggestionService.pendingForSpec(s.reconSpec.id), Vector.empty)
        assertEquals(
          app.matchSuggestionRepo.findAll.filter(_.reconSpecId == s.reconSpec.id).map(_.reviewedByUserId).toSet,
          Set[Option[ID]](Some(reviewer.id))
        )
        assertEquals(app.reconRepo.findByReconSpecID(s.reconSpec.id).size, expectedByDay.last.reconciliations + 1)
    finally scenario.teardown(s)
