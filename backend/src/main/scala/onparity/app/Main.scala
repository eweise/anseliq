package onparity.app

import com.augustnagro.magnum.*
import com.typesafe.config.ConfigFactory
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

object Main:

  val shutdown = java.util.concurrent.CountDownLatch(1)

  def main(args: Array[String]): Unit =
    given DataSource = DbDataSource.create(ConfigFactory.load("application.conf"))
    given transactor:         Transactor             = Transactor(dataSource = given_DataSource)
    given clock:              ClockService           = ClockServiceLive()
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
    given fileSourceFactory:  FileSourceFactory      = S3FileSourceFactory()
    given ledger:             Ledger                 = Ledger()
    given ingestor:           Ingestor               = Ingestor()
    given reconciler:         InMemoryReconciler     = InMemoryReconciler()
    given manualReconciler:   ManualReconciler       = ManualReconciler()
    given disputeRepo:        DisputeRepo            = DisputeRepoLive()
    given disputeCommentRepo: DisputeCommentRepo     = DisputeCommentRepoLive()
    given disputeService:     DisputeService         = DisputeService()
    given dailySnapshotRepo:  DailySnapshotRepo      = DailySnapshotRepoLive()
    given reportingService:   ReportingService       = ReportingService()

    Runtime.getRuntime.addShutdownHook(Thread(() => shutdown.countDown()))

    val logger = Logger(getClass.getName)
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
                val account = transact(Transactor(dataSource = given_DataSource)):
                  accountRepo
                    .findById(job.referenceId)
                    .getOrElse(throw NotFoundError(s"Account ${job.referenceId} not found"))

                logger.debug("found account", kv("account_id", account.id))
                val reconcilationResult = reconciler.reconcileAccountResumable(account.id, jobId = Some(job.id))

                transact(Transactor(dataSource = given_DataSource)):
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
                  transact(Transactor(dataSource = given_DataSource)):
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
              transact(Transactor(dataSource = given_DataSource)):
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
                transact(Transactor(dataSource = given_DataSource)):
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

          transact(Transactor(dataSource = given_DataSource)):
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
            transact(Transactor(dataSource = given_DataSource)):
              jobManager.submitJob(JobType.CreateFileInventory, fileInventoryScanId)
          catch
            case ex: Exception =>
              logger.error(s"Error submitting CreateFileInventory job, will retry in 1m", ex)
        }

      val s3FileSourceFactory = S3FileSourceFactory()

      /**
       * Process FileInventory jobs. Detects new files and creates FileInventory events.
       */
      fork:
        try
          jobManager.pollForJob(JobType.CreateFileInventory, 60): job =>
            try
              transact(Transactor(dataSource = given_DataSource)):
                var filesQueued = 0

                movementSpecRepo.findAll.foreach: spec =>
                  val s3FileSource = s3FileSourceFactory.createFileSource(spec.dataSourceConfig().connectionConfig)
                  val newFiles     = ingestor.detectAndQueue(spec.id, s3FileSource)
                  logger.info(s"File inventory scan: queued ${newFiles.size} new S3 files")
                  filesQueued += newFiles.size

                jobManager.completeJob(job.id, FileInventoryInfo(filesQueued = filesQueued))
            catch
              case ex: Exception =>
                logger.error("CreateFileInventory job failed", kv("job_id", job.id), ex)
                transact(Transactor(dataSource = given_DataSource)):
                  jobManager.markJobInError(job.id, ex)
        catch
          case ex: Exception =>
            logger.error("failed poll for CreateFileInventory job", ex)

      /**
       * Publishes GenerateReconStats jobs (once daily)
       */
      fork:
        val reconStatsScanId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        repeat(Schedule.fixedInterval(24.hours)) {
          try
            transact(Transactor(dataSource = given_DataSource)):
              jobManager.submitJob(JobType.GenerateReconStats, reconStatsScanId)
          catch
            case ex: Exception =>
              logger.error(s"Error submitting GenerateReconStats job, will retry in 24h", ex)
        }

      /**
       * Process GenerateReconStats jobs: compute and store today's per-fund-flow analytics snapshot
       */
      fork:
        try
          jobManager.pollForJob(JobType.GenerateReconStats, 60): job =>
            try
              transact(Transactor(dataSource = given_DataSource)):
                val beginTime = System.currentTimeMillis()
                val count     = reportingService.computeAndStoreDailySnapshots()
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
                transact(Transactor(dataSource = given_DataSource)):
                  jobManager.markJobInError(job.id, ex)
        catch
          case ex: Exception =>
            logger.error("failed poll for GenerateReconStats job", ex)

      new App().main(args)
      shutdown.await() // block until SIGTERM/SIGINT, then scope ends and forks are cancelled
