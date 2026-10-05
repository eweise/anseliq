package anseliq.app

import anseliq.adapter.config.AppContainer
import com.augustnagro.magnum.*
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import net.logstash.logback.argument.StructuredArguments.kv
import anseliq.adapter.driven.*
import anseliq.adapter.driving.*
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.model.JobType.Ingestion
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import anseliq.util.LogContext
import ox.*
import ox.scheduling.{Schedule, repeat}

import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

/**
 * Runs the background jobs and HTTP server against an AppContainer, the production wiring by
 * default. A caller that needs a different service (e.g. a test entry point that shouldn't hit real
 * S3) passes its own AppContainer overriding just that service.
 */
class AppEnv(val app: AppContainer = new AppContainer {}):
  private val logger = Logger(getClass.getName)

  /** Starts every background job fork plus the HTTP server, and blocks until shutdown. */
  def run(args: Array[String]): Unit =
    val shutdownLatch = java.util.concurrent.CountDownLatch(1)

    // set the hours on the clock ahead if arg is present
    args.headOption.foreach: hour =>
      app.clock.addHours(hour.toInt)

    Runtime.getRuntime.addShutdownHook(Thread(() => shutdownLatch.countDown()))

    supervised:

      /**
       * RECONCILIATION: Job looks for Reconciliation jobs. If found then tries to reconcile by the
       * Account ID
       */
      fork:
        try
          app.jobManager.pollForJob(JobType.Reconciliation, 1) { job =>
            LogContext.withContext("job" -> "reconciliation", "job_id" -> job.id):
              try
                val account = transact(Transactor(dataSource = app.dataSource)):
                  app.accountRepo
                    .findById(job.referenceId)
                    .getOrElse(throw NotFoundError(s"Account ${job.referenceId} not found"))

                logger.debug("found account", kv("account_id", account.id))
                val reconcilationResult = app.reconciler.reconcileAccountResumable(account.id, jobId = Some(job.id))

                transact(Transactor(dataSource = app.dataSource)):
                  val jobInfo = ReconciliationInfo(
                    accountId                   = account.id.toString,
                    accountName                 = account.name,
                    reconciliationsCreatedCount = reconcilationResult.totalCount,
                    processingTimeSeconds       = reconcilationResult.processingTimeSeconds
                  )
                  app.jobManager.completeJob(job.id, jobInfo)
              catch
                case ex: Exception =>
                  logger.error("reconciliation job failed", kv("job_id", job.id), ex)
                  transact(Transactor(dataSource = app.dataSource)):
                    app.jobManager.markJobInError(job.id, ex)
          }
        catch
          case ex: Exception =>
            logger.error("failed poll for Reconciliation job", ex)

      /**
       * INGESTION. Listens for New Files. If found then submits ingestion job
       */
      fork:
        try
          app.eventStore.consumeEvent(classOf[NewFileDetected], 1, 10): event =>
            logger.info(s"found NewFileDetected event: $event")
            val fileInventory = app.fileInventoryRepo
              .findById(event.fileInventoryID)
              .ErrIfNotFound("FileInventory", event.fileInventoryID)
            app.jobManager.submitJob(Ingestion, fileInventory.id)
        catch
          case ex: Exception =>
            logger.error("failed eventStore.consumeEvent", ex)
            throw ex

      /**
       * INGESTION. Processes Ingestion jobs
       */
      fork:
        try
          app.jobManager.pollForJob(JobType.Ingestion, 1): job =>
            try
              val results = app.ingestor.ingestFileFromInventory(job.referenceId)
              transact(Transactor(dataSource = app.dataSource)):
                app.jobManager.completeJob(
                  job.id,
                  IngestionInfo( // fixme. should remove these info objects and use events instead
                    numberOfRecordsIngested = results.recordCount,
                    processingTimeSeconds   = results.ingestionTimeSeconds
                  )
                )
            catch
              case ex: Exception =>
                logger.error("failed ingestion job", kv("err", ex.getMessage), kv("job.id", job.referenceId))
                transact(Transactor(dataSource = app.dataSource)):
                  app.jobManager.markJobInError(job.id, ex)
        catch
          case ex: Exception =>
            logger.error("failed poll for Ingestion job", ex)

      /**
       * Create Reconciliation Events: Listens for Ingestion and publishes Reconciliation jobs
       */
      fork:
        app.eventStore.consumeEvent(classOf[MoneyMovementsIngested], 1, 10) { event =>
          logger.info(s"found ingestion job $event")
          val movementSpec = app.movementSpecRepo
            .findById(event.movementSpecID)
            .ErrIfNotFound("movementSpec", event.movementSpecID)

          transact(Transactor(dataSource = app.dataSource)):
            if (app.accountRepo.findById(movementSpec.inAccountId).exists(_.accountType == AccountType.Recon)) then
              app.jobManager.submitJob(JobType.Reconciliation, movementSpec.inAccountId)

            if (app.accountRepo.findById(movementSpec.outAccountId).exists(_.accountType == AccountType.Recon)) then
              app.jobManager.submitJob(JobType.Reconciliation, movementSpec.outAccountId)
        }

      /**
       * Publishes FileInventory jobs
       */
      fork:
        val fileInventoryScanId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        repeat(Schedule.fixedInterval(1.minute)) {
          try
            transact(Transactor(dataSource = app.dataSource)):
              app.jobManager.submitJob(JobType.CreateFileInventory, fileInventoryScanId)
          catch
            case ex: Exception =>
              logger.error(s"Error submitting CreateFileInventory job, will retry in 1m", ex)
        }

      /**
       * Process FileInventory jobs. Detects new files and creates FileInventory events.
       */
      fork:
        try
          app.jobManager.pollForJob(JobType.CreateFileInventory, 60): job =>
            try
              transact(Transactor(dataSource = app.dataSource)):
                var filesQueued = 0

                app.fileFeedRepo.findAll.foreach: feed =>
                  app.ingestor.checkForMissingFile(feed)
                  val fileSource = app.fileSourceFactory.createFileSource(feed.dataSourceConfig().connectionConfig)
                  val newFiles   = app.ingestor.detectAndQueue(feed.id, fileSource)
                  logger.info(s"File inventory scan: queued ${newFiles.size} new S3 files")
                  filesQueued += newFiles.size

                app.jobManager.completeJob(job.id, FileInventoryInfo(filesQueued = filesQueued))
            catch
              case ex: Exception =>
                logger.error("CreateFileInventory job failed", kv("job_id", job.id), ex)
                transact(Transactor(dataSource = app.dataSource)):
                  app.jobManager.markJobInError(job.id, ex)
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
            transact(Transactor(dataSource = app.dataSource)):
              app.jobManager.submitJob(JobType.GenerateReconStats, reconStatsScanId)
          catch
            case ex: Exception =>
              logger.error(s"Error submitting GenerateReconStats job, will retry in 1m", ex)
        }

      /**
       * Process GenerateReconStats jobs: compute and store today's per-fund-flow analytics snapshot
       */
      fork:
        try
          app.jobManager.pollForJob(JobType.GenerateReconStats, 60): job =>
            try
              transact(Transactor(dataSource = app.dataSource)):
                val beginTime = System.currentTimeMillis()
                val count     = app.reportingService.computeAndStoreDailySnapshots()

                val staleMismatchCount = app.reconSpecRepo.findAll.map(app.reconciler.raiseStaleMismatchDisputes).sum
                if staleMismatchCount > 0 then
                  logger.info(s"raised $staleMismatchCount stale OneToMany AmountMismatch dispute(s)")

                app.jobManager.completeJob(
                  job.id,
                  ReconStatsInfo(
                    fundFlowsSnapshotted  = count,
                    processingTimeSeconds = (System.currentTimeMillis() - beginTime) / 1000
                  )
                )
            catch
              case ex: Exception =>
                logger.error("GenerateReconStats job failed", kv("job_id", job.id), ex)
                transact(Transactor(dataSource = app.dataSource)):
                  app.jobManager.markJobInError(job.id, ex)
        catch
          case ex: Exception =>
            logger.error("failed poll for GenerateReconStats job", ex)

      new HttpAPI(
        dataSource         = app.dataSource,
        accountRepo        = app.accountRepo,
        reconRepo          = app.reconRepo,
        fundFlowConfigRepo = app.fundFlowConfigRepo,
        movementSpecRepo   = app.movementSpecRepo,
        fileFeedRepo       = app.fileFeedRepo,
        movementRepo       = app.movementRepo,
        fundFlowOperations = app.fundFlowOperations,
        jobManager         = app.jobManager,
        ingestor           = app.ingestor,
        reconciler         = app.reconciler,
        manualReconciler   = app.manualReconciler,
        jobRepo            = app.jobRepo,
        reconSpecRepo      = app.reconSpecRepo,
        eventRepo          = app.eventRepo,
        disputeRepo        = app.disputeRepo,
        disputeCommentRepo = app.disputeCommentRepo,
        disputeService     = app.disputeService,
        reportingService   = app.reportingService,
        fileInventoryRepo  = app.fileInventoryRepo,
        userRepo           = app.userRepo,
        userService        = app.userService,
        authService        = app.authService,
        jwtSecret          = app.jwtSecret
      ).main(args)
      shutdownLatch.await() // block until SIGTERM/SIGINT, then scope ends and forks are cancelled
