package anseliq.adapter.config

import anseliq.adapter.driven.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.Transactor
import com.typesafe.config.{Config, ConfigFactory}
import org.postgresql.ds.PGSimpleDataSource

import javax.sql.DataSource

trait AppContainer:
  lazy val config: Config = ConfigFactory.load("application.conf")

  lazy val dataSource: DataSource = {
    val source = PGSimpleDataSource()
    source.setCurrentSchema(config.getString("db.schema"))
    source.setServerNames(Array(config.getString("db.host")))
    source.setPortNumber(config.getInt("db.port"))
    source.setDatabaseName(config.getString("db.database_name"))
    source.setUser(config.getString("db.user"))
    source.setPassword(config.getString("db.password"))
    source
  }

  lazy val transactor: Transactor = Transactor(dataSource = dataSource)

  lazy val clock = ClockServiceLive()

  lazy val fileSourceFactory =
    S3FileSourceFactory(
      region   = config.getString("s3.region"),
      endpoint = config.getString("s3.endpoint"),
      username = config.getString("s3.username"),
      password = config.getString("s3.password")
    )

  lazy val jobRepo:            JobRepo                = JobRepoLive()
  lazy val jobManager:         JobManager             = JobManager(jobRepo, clock, dataSource)
  lazy val fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  lazy val reconRepo:          ReconciliationRepo     = ReconciliationRepoLive()
  lazy val movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
  lazy val fileFeedRepo:       FileFeedRepo           = FileFeedRepoLive()
  lazy val reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
  lazy val accountRepo:        AccountRepo            = AccountRepoLive()
  lazy val movementRepo:       MovementRepo           = MovementRepoLive()
  lazy val eventRepo:          EventRepo              = EventRepoLive()
  lazy val fundFlowOperations: FundFlowOperations = FundFlowOperations(
    fundFlowConfigRepo = fundFlowConfigRepo,
    accountRepo        = accountRepo,
    movementRepo       = movementRepo,
    movementSpecRepo   = movementSpecRepo,
    fileFeedRepo       = fileFeedRepo,
    reconSpecRepo      = reconSpecRepo
  )
  lazy val eventStore:        EventStore        = EventStore(eventRepo, clock, dataSource)
  lazy val fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()
  lazy val ledger: Ledger =
    Ledger(
      movementRepo = movementRepo,
      accountRepo  = accountRepo,
      reconRepo    = reconRepo,
      clock        = clock
    )
  lazy val disputeRepo:        DisputeRepo        = DisputeRepoLive()
  lazy val disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive()
  lazy val userRepo:           UserRepo           = UserRepoLive()
  lazy val disputeService: DisputeService = DisputeService(
    disputeRepo      = disputeRepo,
    commentRepo      = disputeCommentRepo,
    movementRepo     = movementRepo,
    reconRepo        = reconRepo,
    movementSpecRepo = movementSpecRepo,
    fileFeedRepo     = fileFeedRepo,
    userRepo         = userRepo,
    eventStore       = eventStore,
    clock            = clock
  )
  lazy val reconciler: InMemoryReconciler = InMemoryReconciler(
    ledger             = ledger,
    movementRepo       = movementRepo,
    reconSpecRepo      = reconSpecRepo,
    movementSpecRepo   = movementSpecRepo,
    reconRepo          = reconRepo,
    fundFlowConfigRepo = fundFlowConfigRepo,
    eventStore         = eventStore,
    clock              = clock,
    transactor         = transactor,
    jobRepo            = jobRepo,
    disputeService     = disputeService,
    dataSource         = dataSource
  )
  lazy val manualReconciler: ManualReconciler = ManualReconciler(
    ledger         = ledger,
    movementRepo   = movementRepo,
    reconRepo      = reconRepo,
    reconSpecRepo  = reconSpecRepo,
    disputeService = disputeService,
    eventStore     = eventStore,
    clock          = clock,
    transactor     = transactor
  )
  lazy val dailySnapshotRepo: DailySnapshotRepo = DailySnapshotRepoLive()
  lazy val reportingService: ReportingService = ReportingService(
    accountRepo        = accountRepo,
    disputeRepo        = disputeRepo,
    movementRepo       = movementRepo,
    reconRepo          = reconRepo,
    reconSpecRepo      = reconSpecRepo,
    movementSpecRepo   = movementSpecRepo,
    fundFlowConfigRepo = fundFlowConfigRepo,
    dailySnapshotRepo  = dailySnapshotRepo,
    clock              = clock
  )
  lazy val userService: UserService = UserService(userRepo, clock)
  lazy val jwtSecret:   JwtSecret   = JwtSecret(config.getString("jwt.secret"))
  lazy val authService: AuthService = AuthService(userRepo, jwtSecret)
  lazy val ingestor = Ingestor(
    ledger            = ledger,
    movementRepo      = movementRepo,
    movementSpecRepo  = movementSpecRepo,
    fileFeedRepo      = fileFeedRepo,
    eventStore        = eventStore,
    fileInventoryRepo = fileInventoryRepo,
    fileSourceFactory = fileSourceFactory,
    disputeService    = disputeService,
    clock             = clock,
    transactor        = transactor
  )
