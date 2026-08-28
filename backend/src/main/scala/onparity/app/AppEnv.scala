package onparity.app

import com.augustnagro.magnum.*
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import net.logstash.logback.argument.StructuredArguments.kv
import onparity.adapter.driven.*
import onparity.adapter.driving.*
import onparity.common.model.*
import onparity.domain.model.*
import onparity.domain.model.JobType.Ingestion
import onparity.domain.port.driven.*
import onparity.domain.service.*
import onparity.util.LogContext
import ox.*
import ox.scheduling.{Schedule, repeat}

import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

/**
 * Wires every driven-side adapter behind a `given`, with production adapters as defaults. A caller
 * that needs a fake adapter (e.g. a test entry point that shouldn't hit real S3) builds its own
 * `AppEnv` overriding just that constructor parameter, instead of hand-duplicating this entire
 * wiring block the way EndToEndSuite historically did.
 */
class AppEnv(
  config:            Config            = ConfigFactory.load("application.conf"),
  clock:             ClockService      = ClockServiceLive(),
  fileSourceFactory: FileSourceFactory = S3FileSourceFactory()
):
  private val logger = Logger(getClass.getName)

  given dataSource: DataSource = DbDataSource.create(config)
  given transactor: Transactor = Transactor(dataSource = dataSource)
  given ClockService      = clock
  given FileSourceFactory = fileSourceFactory

  given jobRepo:            JobRepo                = JobRepoLive()
  given jobManager:         JobManager             = JobManager()
  given fundFlowConfigRepo: FundFlowConfigRepo     = FundFlowConfigRepoLive()
  given reconRepo:          ReconciliationRepo     = ReconciliationRepoLive()
  given movementSpecRepo:   MovementSpecRepo       = MovementSpecRepoLive()
  given reconSpecRepo:      ReconciliationSpecRepo = ReconciliationSpecRepoLive()
  given accountRepo:        AccountRepo            = AccountRepoLive()
  given movementRepo:       MovementRepo           = MovementRepoLive()
  given eventRepo:          EventRepo              = EventRepoLive()
  given fundFlowOperations: FundFlowOperations     = FundFlowOperations()
  given eventStore:         EventStore             = EventStore()
  given fileInventoryRepo:  FileInventoryRepo      = FileInventoryRepoLive()
  given ledger:             Ledger                 = Ledger()
  given disputeRepo:        DisputeRepo            = DisputeRepoLive()
  given disputeCommentRepo: DisputeCommentRepo     = DisputeCommentRepoLive()
  given reconciler:         InMemoryReconciler     = InMemoryReconciler()
  given manualReconciler:   ManualReconciler       = ManualReconciler()
  given disputeService:     DisputeService         = DisputeService()
  given dailySnapshotRepo:  DailySnapshotRepo      = DailySnapshotRepoLive()
  given reportingService:   ReportingService       = ReportingService()
  given userRepo:           UserRepo               = UserRepoLive()
  given userService:        UserService            = UserService()
  given jwtSecret:          JwtSecret              = JwtSecret(config.getString("jwt.secret"))
  given authService:        AuthService            = AuthService()

  // Ingestor isn't a plain constructor parameter like fileSourceFactory/clock because its default
  // (Ingestor()) itself needs ~10 sibling givens (ledger, movementRepo, ...) declared above — a
  // constructor parameter's default value can't see those (it's evaluated before this instance
  // exists). Overriding this hook in an anonymous AppEnv subclass instead works because the
  // override body runs with those givens already inherited and in scope, e.g.:
  //   new AppEnv(...):
  //     override protected def buildIngestor: Ingestor = ControlTimeIngestor()
  protected def buildIngestor: Ingestor = Ingestor()
  given ingestor:              Ingestor = buildIngestor

  /** Starts every background job fork plus the HTTP server, and blocks until shutdown. */
  def run(args: Array[String]): Unit =
    val shutdownLatch = java.util.concurrent.CountDownLatch(1)

    // set the hours on the clock ahead if arg is present
    args.headOption.foreach: hour =>
      clock.addHours(hour.toInt)

    Runtime.getRuntime.addShutdownHook(Thread(() => shutdownLatch.countDown()))

    supervised:

      /**
       * RECONCILIATION: Job looks for Reconciliation jobs. If found then tries to reconcile by the
       * Account ID
       */
      fork:
        try
          jobManager.pollForJob(JobType.Reconciliation, 5) { job =>
            LogContext.withContext("job" -> "reconciliation", "job_id" -> job.id):
              try
                val account = transact(Transactor(dataSource = dataSource)):
                  accountRepo
                    .findById(job.referenceId)
                    .getOrElse(throw NotFoundError(s"Account ${job.referenceId} not found"))

                logger.debug("found account", kv("account_id", account.id))
                val reconcilationResult = reconciler.reconcileAccountResumable(account.id, jobId = Some(job.id))

                transact(Transactor(dataSource = dataSource)):
                  val jobInfo = ReconciliationInfo(
                    accountId                   = account.id.toString,
                    accountName                 = account.name,
                    reconciliationsCreatedCount = reconcilationResult.totalCount,
                    processingTimeSeconds       = reconcilationResult.processingTimeSeconds
                  )
                  jobManager.completeJob(job.id, jobInfo)
              catch
                case ex: Exception =>
                  logger.error("reconciliation job failed", kv("job_id", job.id), ex)
                  transact(Transactor(dataSource = dataSource)):
                    jobManager.markJobInError(job.id, ex)
          }
        catch
          case ex: Exception =>
            logger.error("failed poll for Reconciliation job", ex)

      /**
       * INGESTION. Listens for New Files. If found then submits ingestion job
       */
      fork:
        try
          eventStore.consumeEvent(classOf[NewFileDetected], 5, 10): event =>
            logger.info(s"found NewFileDetected event: $event")
            val fileInventory = fileInventoryRepo
              .findById(event.fileInventoryID)
              .ErrIfNotFound("FileInventory", event.fileInventoryID)
            jobManager.submitJob(Ingestion, fileInventory.id)
        catch
          case ex: Exception =>
            logger.error("failed eventStore.consumeEvent", ex)
            throw ex

      /**
       * INGESTION. Processes Ingestion jobs
       */
      fork:
        try
          jobManager.pollForJob(JobType.Ingestion, 5): job =>
            try
              val results = ingestor.ingestFileFromInventory(job.referenceId)
              transact(Transactor(dataSource = dataSource)):
                jobManager.completeJob(
                  job.id,
                  IngestionInfo( // fixme. should remove these info objects and use events instead
                    numberOfRecordsIngested = results.recordCount,
                    processingTimeSeconds   = results.ingestionTimeSeconds
                  )
                )
            catch
              case ex: Exception =>
                logger.error("failed ingestion job", kv("err", ex.getMessage), kv("job.id", job.referenceId))
                transact(Transactor(dataSource = dataSource)):
                  jobManager.markJobInError(job.id, ex)
        catch
          case ex: Exception =>
            logger.error("failed poll for Ingestion job", ex)

      /**
       * Create Reconciliation Events: Listens for Ingestion and publishes Reconciliation jobs
       */
      fork:
        eventStore.consumeEvent(classOf[MoneyMovementsIngested], 5, 10) { event =>
          logger.info(s"found ingestion job $event")
          val movementSpec = movementSpecRepo
            .findById(event.movementSpecID)
            .ErrIfNotFound("movementSpec", event.movementSpecID)

          transact(Transactor(dataSource = dataSource)):
            if (accountRepo.isReconAccount(movementSpec.inAccountId)) then
              jobManager.submitJob(JobType.Reconciliation, movementSpec.inAccountId)

            if (accountRepo.isReconAccount(movementSpec.outAccountId)) then
              jobManager.submitJob(JobType.Reconciliation, movementSpec.outAccountId)
        }

      /**
       * Publishes FileInventory jobs
       */
      fork:
        val fileInventoryScanId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        repeat(Schedule.fixedInterval(1.minute)) {
          try
            transact(Transactor(dataSource = dataSource)):
              jobManager.submitJob(JobType.CreateFileInventory, fileInventoryScanId)
          catch
            case ex: Exception =>
              logger.error(s"Error submitting CreateFileInventory job, will retry in 1m", ex)
        }

      /**
       * Process FileInventory jobs. Detects new files and creates FileInventory events.
       */
      fork:
        try
          jobManager.pollForJob(JobType.CreateFileInventory, 60): job =>
            try
              transact(Transactor(dataSource = dataSource)):
                var filesQueued = 0

                movementSpecRepo.findAll.foreach: spec =>
                  ingestor.checkForMissingFile(spec)
                  val fileSource = fileSourceFactory.createFileSource(spec.dataSourceConfig().connectionConfig)
                  val newFiles   = ingestor.detectAndQueue(spec.id, fileSource)
                  logger.info(s"File inventory scan: queued ${newFiles.size} new S3 files")
                  filesQueued += newFiles.size

                jobManager.completeJob(job.id, FileInventoryInfo(filesQueued = filesQueued))
            catch
              case ex: Exception =>
                logger.error("CreateFileInventory job failed", kv("job_id", job.id), ex)
                transact(Transactor(dataSource = dataSource)):
                  jobManager.markJobInError(job.id, ex)
        catch
          case ex: Exception =>
            logger.error("failed poll for CreateFileInventory job", ex)

      /**
       * Publishes GenerateReconStats jobs. Runs every minute rather than once every 24 hours
       * because "due" now depends on each fund flow's own dailyCutoffHours, not on when the app
       * happened to start — computeAndStoreDailySnapshots itself is the cheap no-op once a fund
       * flow's snapshot for today already exists, so a tight poll interval is safe here.
       */
      fork:
        val reconStatsScanId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        repeat(Schedule.fixedInterval(1.minute)) {
          try
            transact(Transactor(dataSource = dataSource)):
              jobManager.submitJob(JobType.GenerateReconStats, reconStatsScanId)
          catch
            case ex: Exception =>
              logger.error(s"Error submitting GenerateReconStats job, will retry in 1m", ex)
        }

      /**
       * Process GenerateReconStats jobs: compute and store today's per-fund-flow analytics snapshot
       */
      fork:
        try
          jobManager.pollForJob(JobType.GenerateReconStats, 60): job =>
            try
              transact(Transactor(dataSource = dataSource)):
                val beginTime = System.currentTimeMillis()
                val count     = reportingService.computeAndStoreDailySnapshots()

                val staleMismatchCount = reconSpecRepo.findAll.map(reconciler.raiseStaleMismatchDisputes).sum
                if staleMismatchCount > 0 then
                  logger.info(s"raised $staleMismatchCount stale OneToMany AmountMismatch dispute(s)")

                jobManager.completeJob(
                  job.id,
                  ReconStatsInfo(
                    fundFlowsSnapshotted  = count,
                    processingTimeSeconds = (System.currentTimeMillis() - beginTime) / 1000
                  )
                )
            catch
              case ex: Exception =>
                logger.error("GenerateReconStats job failed", kv("job_id", job.id), ex)
                transact(Transactor(dataSource = dataSource)):
                  jobManager.markJobInError(job.id, ex)
        catch
          case ex: Exception =>
            logger.error("failed poll for GenerateReconStats job", ex)

      new App().main(args)
      shutdownLatch.await() // block until SIGTERM/SIGINT, then scope ends and forks are cancelled
