package anseliq.testing

import anseliq.adapter.driven.LocalLlmMatchSuggester
import anseliq.app.AppEnv
import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.{FileSourceFactory, MatchSuggester}
import com.augustnagro.magnum.transact
import com.typesafe.config.{Config, ConfigFactory}

/**
 * One fund flow run day by day through the real pipeline, for HappyPathScenarioSuite to check and
 * SeedHappyPathDemo to leave in the dev DB.
 *
 * A Journal account posts into a Recon account and the Recon account settles out to a Bank
 * account, matched one-to-one on transaction id. Each day a journal file and a bank file land, and
 * the day runs the same steps the background jobs do: scan the feeds, ingest each new file, then
 * reconcile and suggest matches for what's left, as jobs, then take the day's snapshot. The clock
 * starts Days - 1 days back and moves forward a day at a time, so the last day is today and the
 * data reads as recent history.
 *
 * What lands, so the expected numbers in HappyPathScenarioSuite can be worked out by hand:
 *   - every day: three transactions that settle the same day
 *   - days 0, 3 and 6: one more that settles the next day
 *   - day 0: one that never settles, aging from 0 days on day 0 to Days - 1 days on the last day
 *   - days 2, 4 and 6: one whose references differ only in formatting (ReviewedFuzzyPairs), which
 *     the rule can't match but the suggester can; a reviewer accepts it the next morning
 *   - the last day: two more (PendingFuzzyPairs), left as pending suggestions for the demo
 *
 * So by the end, normalize(transaction_id) = normalize(txn_id) has explained three accepted
 * suggestions, and the spec page offers it as a learned rule that would match the two pending pairs.
 */
object HappyPathScenario:
  val Days           = 10
  val AgingLimitDays = 5
  val LateDays       = Set(0, 3, 6)
  val StuckId        = "T0-STUCK"
  // (journal reference, bank reference, amount), by the day they land; each is accepted the day after.
  val ReviewedFuzzyPairs = Map(
    2 -> ("INV-2026-0012", "inv20260012", BigDecimal("640.00")),
    4 -> ("INV-2026-0019", "inv20260019", BigDecimal("1890.25")),
    6 -> ("INV-2026-0027", "inv20260027", BigDecimal("312.40"))
  )
  // Land on the last day and stay pending.
  val PendingFuzzyPairs = Seq(
    ("INV-2026-0042", "inv20260042", BigDecimal("1250.00")),
    ("PO 7781/A", "PO7781A", BigDecimal("89.90"))
  )

  case class DayFiles(journal: Seq[(String, BigDecimal)], bank: Seq[(String, BigDecimal)])

  def filesFor(day: Int): DayFiles =
    val sameDay = Seq(s"T$day-1" -> BigDecimal(100 + day), s"T$day-2" -> BigDecimal("250.50"), s"T$day-3" -> BigDecimal("75.25"))
    val late    = if LateDays.contains(day) then Seq(s"T$day-LATE" -> BigDecimal(500)) else Nil
    val settled = if LateDays.contains(day - 1) then Seq(s"T${day - 1}-LATE" -> BigDecimal(500)) else Nil
    val stuck   = if day == 0 then Seq(StuckId -> BigDecimal("42.00")) else Nil
    val fuzzy   = ReviewedFuzzyPairs.get(day).toSeq ++ (if day == Days - 1 then PendingFuzzyPairs else Nil)
    DayFiles(
      journal = sameDay ++ late ++ stuck ++ fuzzy.map((ref, _, amount) => ref -> amount),
      bank    = sameDay ++ settled ++ fuzzy.map((_, ref, amount) => ref -> amount)
    )

  case class Setup(
    runId:        String,
    fundFlow:     FundFlowConfig,
    journal:      Account,
    recon:        Account,
    bank:         Account,
    journalFeed:  FileFeed,
    bankFeed:     FileFeed,
    journalSpec:  MovementSpec,
    bankSpec:     MovementSpec,
    reconSpec:    ReconciliationSpec,
    // Accepts the previous day's match suggestions each morning.
    reviewer: User
  )

// The production wiring with files coming from memory instead of S3, and match suggestions turned
// on: by ReferenceFormatSuggester standing in for a model, or with useLocalLlm the configured local
// model (match-suggestions.local-llm), for demos.
class HappyPathApp(useLocalLlm: Boolean = false) extends TestAppContainer:
  lazy val files: InMemoryFileSources                   = InMemoryFileSources(clock)
  override lazy val fileSourceFactory: FileSourceFactory = files
  override lazy val matchSuggester: MatchSuggester =
    if useLocalLlm then LocalLlmMatchSuggester.fromConfig(config) else ReferenceFormatSuggester()
  override protected def configOverrides: Config         = ConfigFactory.parseString("match-suggestions.enabled = true")

class HappyPathScenario(app: HappyPathApp):
  import HappyPathScenario.*

  private val env = AppEnv(app)

  /** Moves the clock back to day 0 and creates the fund flow, committed. */
  def start(): Setup =
    app.clock.addHours(-24 * (Days - 1))
    val runId = java.util.UUID.randomUUID().toString.take(8)
    transact(app.transactor):
      val fundFlow = app.fundFlowService.createFundFlow(FundFlowConfig(name = s"Happy Path $runId"))
      val journal  = app.accountService.createAccount(Account(name = "Journal", fundFlowId = fundFlow.id, accountType = AccountType.Primary))
      val recon    = app.accountService.createAccount(Account(name = "Recon", fundFlowId = fundFlow.id, accountType = AccountType.Recon))
      val bank     = app.accountService.createAccount(Account(name = "Bank", fundFlowId = fundFlow.id, accountType = AccountType.Secondary))

      def feed(name: String, idColumn: String) = app.fileFeedService.createFileFeed(
        FileFeed(
          name                 = s"$name Feed $runId",
          expectedCadenceHours = Some(24),
          dataSourceJson = upickle.write(
            DataSourceConfig(
              parserConfig = CsvParserConfig(
                hasHeader         = false,
                columnDefinitions = Map(0 -> idColumn, 1 -> "amount"),
                amountField       = Some(1)
              ),
              connectionConfig = S3ConnectionConfig(s3Bucket = "happy-path", filePath = s"$runId/${name.toLowerCase}")
            )
          )
        )
      )
      val journalFeed = feed("Journal", "transaction_id")
      val bankFeed    = feed("Bank", "txn_id")

      val journalSpec = app.movementSpecService.createMovementSpec(
        MovementSpec(name = "Journal to Recon", outAccountId = journal.id, inAccountId = recon.id, fileFeedId = journalFeed.id)
      )
      val bankSpec = app.movementSpecService.createMovementSpec(
        MovementSpec(name = "Recon to Bank", outAccountId = recon.id, inAccountId = bank.id, fileFeedId = bankFeed.id)
      )
      val reconSpec = app.reconSpecService.createReconSpec(
        ReconciliationSpec(
          accountId         = recon.id,
          agingLimit        = AgingLimitDays,
          inMovementSpecId  = journalSpec.id,
          outMovementSpecId = bankSpec.id,
          ruleJson = upickle.write[Rule](
            StringAttributeMatchRule(inKey = StringAttribute(key = "transaction_id"), outKey = StringAttribute(key = "txn_id"))
          )
        )
      )
      val reviewer = app.userService.createUser(
        s"happy-path-$runId@example.com",
        "Rita",
        "Reviewer",
        UserRole.Analyst,
        java.util.UUID.randomUUID().toString
      )
      Setup(runId, fundFlow, journal, recon, bank, journalFeed, bankFeed, journalSpec, bankSpec, reconSpec, reviewer)

  /** Runs every day, calling `afterDay` with each day's number once it's done, before the clock moves on. */
  def runDays(s: Setup)(afterDay: Int => Unit = _ => ()): Unit =
    (0 until Days).foreach: day =>
      if day > 0 then
        app.clock.addHours(24)
        acceptYesterdaysSuggestions(s)
      runDay(s, day)
      afterDay(day)

  // The reviewer works through the previous day's suggestions first thing, accepting each one.
  private def acceptYesterdaysSuggestions(s: Setup): Unit =
    transact(app.transactor):
      app.matchSuggestionService
        .pendingForSpec(s.reconSpec.id)
        .foreach(suggestion => app.matchSuggestionService.accept(suggestion.id, s.reviewer.id))

  private def csv(rows: Seq[(String, BigDecimal)]): String = rows.map((id, amount) => s"$id,$amount\n").mkString

  private def runDay(s: Setup, day: Int): Unit =
    val files = filesFor(day)
    app.files.drop(s.journalFeed.dataSourceConfig().connectionConfig, s"journal-day$day.csv", csv(files.journal))
    app.files.drop(s.bankFeed.dataSourceConfig().connectionConfig, s"bank-day$day.csv", csv(files.bank))

    val ingestionJobs = transact(app.transactor)(Seq(s.journalFeed, s.bankFeed).flatMap(env.scanFileFeed))
    ingestionJobs.foreach(jobId => runJob(jobId)(env.ingest))

    // Each ingestion queued a Reconciliation job for the Recon account; submitJob kept it to one.
    val reconJobs = transact(app.transactor):
      app.jobRepo
        .findByJobTypeAndReferenceId(JobType.Reconciliation, s.recon.id)
        .filter(_.jobStatus == JobStatus.Submitted)
    reconJobs.foreach(job => runJob(job.id)(j => app.reconciliationRunner.runJob(j.referenceId, j.id)))

    // The reconciliation queued a SuggestMatches job for what it left unmatched.
    val suggestJobs = transact(app.transactor):
      app.jobRepo
        .findByJobTypeAndReferenceId(JobType.SuggestMatches, s.recon.id)
        .filter(_.jobStatus == JobStatus.Submitted)
    suggestJobs.foreach(job => runJob(job.id)(j => app.matchSuggestionService.suggestForAccount(j.referenceId)))

    transact(app.transactor):
      app.reportingService.storeDailySnapshot(s.fundFlow)
      app.reconciler.raiseStaleMismatchDisputes(s.reconSpec)

  // Claims and runs a queued job the way JobManager.runWorker does, but synchronously and only for
  // this scenario's jobs, so other queued jobs in the shared DB are left alone.
  private def runJob(jobId: ID)(work: Job => JobInfo): Unit =
    val job = transact(app.transactor):
      app.jobManager.claimJob(jobId)
      app.jobRepo.findById(jobId).get
    try
      val info = app.jobManager.withLease(jobId)(work(job))
      transact(app.transactor)(app.jobManager.completeJob(jobId, info))
    catch
      case ex: Exception =>
        transact(app.transactor)(app.jobManager.markJobInError(jobId, ex))
        throw ex

  /** Deletes everything the scenario created. */
  def teardown(s: Setup): Unit =
    transact(app.transactor):
      val feedIds      = Set(s.journalFeed.id, s.bankFeed.id)
      val accountIds   = Set(s.journal.id, s.recon.id, s.bank.id)
      val inventoryIds = app.fileInventoryRepo.findAll.filter(fi => feedIds.contains(fi.fileFeedId)).map(_.id).toSet
      val recons       = app.reconRepo.findByReconSpecID(s.reconSpec.id)
      val movementIds  = accountIds.flatMap(app.movementRepo.findByAccount(_).map(_.id))
      val disputes = app.disputeRepo.findAll.filter: d =>
        (feedIds ++ recons.map(_.id) ++ movementIds + s.journalSpec.id + s.bankSpec.id).contains(d.referenceId)

      // Events only carry ids in their JSON payload.
      val idsInEvents =
        (accountIds ++ inventoryIds ++ disputes.map(_.id) ++ recons.map(_.id) + s.journalSpec.id + s.bankSpec.id).map(_.toString)
      app.eventRepo.findAll
        .filter(e => idsInEvents.exists(e.payload.contains))
        .foreach(e => app.eventRepo.deleteById(e.id))

      disputes.foreach(d => app.disputeRepo.deleteById(d.id))
      inventoryIds.foreach: id =>
        app.jobRepo.findByJobTypeAndReferenceId(JobType.Ingestion, id).foreach(j => app.jobRepo.deleteById(j.id))
        app.fileInventoryRepo.deleteById(id)
      Seq(JobType.Reconciliation, JobType.SuggestMatches).foreach: jobType =>
        app.jobRepo.findByJobTypeAndReferenceId(jobType, s.recon.id).foreach(j => app.jobRepo.deleteById(j.id))
      // Deleting a reconciliation deletes its movements (movement.reconciliation_id cascades).
      recons.foreach(r => app.reconRepo.deleteById(r.id))
      app.reconSpecRepo.deleteById(s.reconSpec.id)
      // Cascades to the accounts, and from them to the movement specs, remaining movements (and their
      // match suggestions) and daily snapshots.
      app.fundFlowConfigRepo.deleteById(s.fundFlow.id)
      feedIds.foreach(app.fileFeedRepo.deleteById)
      app.userRepo.deleteById(s.reviewer.id)
