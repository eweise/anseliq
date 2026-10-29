package anseliq.adapter.config

import anseliq.adapter.driven.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.Transactor
import com.typesafe.config.{Config, ConfigFactory}

import java.nio.charset.StandardCharsets
import javax.sql.DataSource

trait AppContainer:
  lazy val config: Config = ConfigFactory.load("application.conf")

  lazy val dataSource: DataSource = DbDataSource.pooled(config)
  // Only for the reconciler's cursors; see DbDataSource.unpooled.
  lazy val cursorDataSource: DataSource = DbDataSource.unpooled(config)

  lazy val transactor: Transactor = Transactor(dataSource = dataSource)

  lazy val clock = ClockServiceLive()

  lazy val fileSourceFactory: FileSourceFactory = S3FileSourceFactory.fromConfig(config)

  lazy val jobRepo:             JobRepo                = JobRepoLive(clock)
  lazy val jobManager:          JobManager             = JobManager(jobRepo, clock, dataSource)
  lazy val ledgerRepo:          LedgerRepo             = LedgerRepoLive(clock)
  lazy val reconRepo:           ReconciliationRepo     = ReconciliationRepoLive(clock)
  lazy val movementSpecRepo:    MovementSpecRepo       = MovementSpecRepoLive(clock)
  lazy val fileFeedRepo:        FileFeedRepo           = FileFeedRepoLive(clock)
  lazy val reconSpecRepo:       ReconciliationSpecRepo = ReconciliationSpecRepoLive(clock)
  lazy val accountRepo:         AccountRepo            = AccountRepoLive(clock)
  lazy val movementRepo:        MovementRepo           = MovementRepoLive(clock)
  lazy val eventRepo:           EventRepo              = EventRepoLive()
  lazy val ledgerService:       LedgerService          = LedgerService(ledgerRepo)
  lazy val accountService:      AccountService         = AccountService(accountRepo, movementRepo)
  lazy val movementSpecService: MovementSpecService    = MovementSpecService(movementSpecRepo, accountRepo)
  lazy val fileFeedService:     FileFeedService        = FileFeedService(fileFeedRepo)
  lazy val reconSpecService:    ReconSpecService       = ReconSpecService(reconSpecRepo, movementSpecRepo, accountRepo)
  lazy val eventStore:          EventStore             = EventStore(eventRepo, clock)
  lazy val fileInventoryRepo:   FileInventoryRepo      = FileInventoryRepoLive()
  lazy val bookkeeper: Bookkeeper =
    Bookkeeper(
      movementRepo = movementRepo,
      accountRepo  = accountRepo,
      reconRepo    = reconRepo,
      clock        = clock
    )
  lazy val exceptionRepo:        ExceptionRepo        = ExceptionRepoLive()
  lazy val exceptionCommentRepo: ExceptionCommentRepo = ExceptionCommentRepoLive()
  lazy val userRepo:             UserRepo             = UserRepoLive(clock)
  lazy val exceptionService: ExceptionService = ExceptionService(
    exceptionRepo    = exceptionRepo,
    commentRepo      = exceptionCommentRepo,
    movementRepo     = movementRepo,
    reconRepo        = reconRepo,
    movementSpecRepo = movementSpecRepo,
    fileFeedRepo     = fileFeedRepo,
    userRepo         = userRepo,
    eventStore       = eventStore,
    clock            = clock
  )
  lazy val reconciler: InMemoryReconciler = InMemoryReconciler(
    bookkeeper       = bookkeeper,
    movementRepo     = movementRepo,
    reconSpecRepo    = reconSpecRepo,
    movementSpecRepo = movementSpecRepo,
    reconRepo        = reconRepo,
    ledgerRepo       = ledgerRepo,
    eventStore       = eventStore,
    clock            = clock,
    transactor       = transactor,
    jobRepo          = jobRepo,
    exceptionService = exceptionService,
    cursorDataSource = cursorDataSource
  )
  lazy val reconciliationRunner: ReconciliationRunner = ReconciliationRunner(
    accountRepo      = accountRepo,
    movementSpecRepo = movementSpecRepo,
    jobManager       = jobManager,
    reconciler       = reconciler,
    transactor       = transactor,
    suggestMatches   = config.getBoolean("match-suggestions.enabled")
  )
  lazy val matchSuggestionRepo: MatchSuggestionRepo = MatchSuggestionRepoLive(clock)
  // See MatchSuggester for why an adapter must not call out of the install.
  lazy val matchSuggester: MatchSuggester = config.getString("match-suggestions.adapter") match
    case "local-llm" => LocalLlmMatchSuggester.fromConfig(config)
    case "disabled"  => DisabledMatchSuggester()
    case other       => throw IllegalArgumentException(s"unknown match-suggestions.adapter: $other")
  lazy val matchSuggestionService: MatchSuggestionService = MatchSuggestionService(
    reconSpecRepo           = reconSpecRepo,
    reconRepo               = reconRepo,
    movementRepo            = movementRepo,
    matchSuggestionRepo     = matchSuggestionRepo,
    suggester               = matchSuggester,
    manualReconciler        = manualReconciler,
    transactor              = transactor,
    candidateWindowDays     = config.getInt("match-suggestions.candidate-window-days"),
    maxCandidates           = config.getInt("match-suggestions.max-candidates"),
    ruleProposalMinExamples = config.getInt("match-suggestions.rule-proposal-min-examples")
  )
  lazy val manualReconciler: ManualReconciler = ManualReconciler(
    bookkeeper       = bookkeeper,
    movementRepo     = movementRepo,
    reconRepo        = reconRepo,
    reconSpecRepo    = reconSpecRepo,
    exceptionService = exceptionService,
    eventStore       = eventStore,
    clock            = clock,
    transactor       = transactor
  )
  lazy val dailySnapshotRepo: DailySnapshotRepo = DailySnapshotRepoLive(clock)
  lazy val reportingService: ReportingService = ReportingService(
    accountRepo       = accountRepo,
    exceptionRepo     = exceptionRepo,
    movementRepo      = movementRepo,
    reconRepo         = reconRepo,
    reconSpecRepo     = reconSpecRepo,
    movementSpecRepo  = movementSpecRepo,
    ledgerRepo        = ledgerRepo,
    dailySnapshotRepo = dailySnapshotRepo,
    clock             = clock
  )
  lazy val userService: UserService = UserService(userRepo, clock)
  // No default, so an instance can't end up signing tokens with a secret anyone can read in this
  // repo. HS256 wants a key at least as long as its 256-bit hash (RFC 7518 section 3.2).
  private val MinJwtSecretBytes = 32
  lazy val jwtSecret: JwtSecret =
    if !config.hasPath("jwt.secret") then
      throw IllegalStateException("JWT_SECRET is not set; the HTTP server needs it to sign and check login tokens")
    val secret = config.getString("jwt.secret")
    if secret.getBytes(StandardCharsets.UTF_8).length < MinJwtSecretBytes then
      throw IllegalStateException(s"JWT_SECRET must be at least $MinJwtSecretBytes bytes")
    JwtSecret(secret)
  lazy val authService: AuthService = AuthService(userRepo, jwtSecret)
  lazy val ingestor = Ingestor(
    bookkeeper        = bookkeeper,
    movementRepo      = movementRepo,
    movementSpecRepo  = movementSpecRepo,
    fileFeedRepo      = fileFeedRepo,
    eventStore        = eventStore,
    fileInventoryRepo = fileInventoryRepo,
    fileSourceFactory = fileSourceFactory,
    exceptionService  = exceptionService,
    clock             = clock,
    transactor        = transactor
  )
