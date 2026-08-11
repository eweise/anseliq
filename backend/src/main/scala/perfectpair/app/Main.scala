package perfectpair.app

import com.augustnagro.magnum.*
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import ox.*
import ox.scheduling.{Schedule, repeat}
import perfectpair.adapter.driven.*
import perfectpair.adapter.driving.*
import perfectpair.common.model.*
import perfectpair.domain.model.*
import perfectpair.domain.model.JobType.Ingestion
import perfectpair.domain.port.driven.*
import perfectpair.domain.service.*

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
            try
              transact(Transactor(dataSource = given_DataSource)):
                val account =
                  accountRepo
                    .findById(job.referenceId)
                    .getOrElse(throw NotFoundError(s"Account ${job.referenceId} not found"))

                val reconcilationResult = reconciler.reconcileAccount(account.id)

                val jobInfo = ReconciliationInfo(
                  accountId                   = account.id.toString,
                  accountName                 = account.name,
                  reconciliationsCreatedCount = reconcilationResult.totalCount,
                  processingTimeSeconds       = reconcilationResult.processingTimeSeconds
                )
                jobManager.completeJob(job.id, jobInfo)
            catch
              case ex: Exception =>
                ex.printStackTrace()
                throw ex
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
        jobManager.pollForJob(JobType.Ingestion, 5): job =>
          try {
            val results = ingestor.ingestFileFromInventory(job.referenceId)
            transact(Transactor(dataSource = given_DataSource)):
              jobManager.completeJob(
                job.id,
                IngestionInfo( // fixme. should remove these info objects and use events instead
                  numberOfRecordsIngested = results.recordCount,
                  processingTimeSeconds   = results.ingestionTimeSeconds
                )
              )
          } catch
            case ex: Exception =>
              throw new Exception(s"process ingestion jobs. job.referenceId = ${job.referenceId}", ex)

      /**
       * Create Reconciliation Events: Listens for Ingestion and publishes Reconciliation jobs
       */
      fork:
        try
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
        catch case ex: Exception => ex.printStackTrace()

      /**
       * Publishes FileInventory jobs
       */
      fork:
        val fileInventoryScanId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        repeat(Schedule.fixedInterval(5.minute)) {
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
            transact(Transactor(dataSource = given_DataSource)):
              var filesQueued = 0

              movementSpecRepo.findAll.foreach: spec =>
                val s3FileSource = s3FileSourceFactory.createFileSource(spec.dataSourceConfig().connectionConfig)
                val newFiles     = ingestor.detectAndQueue(spec.id, s3FileSource)
                logger.info(s"File inventory scan: queued ${newFiles.size} new S3 files")
                filesQueued += newFiles.size

              jobManager.completeJob(job.id, FileInventoryInfo(filesQueued = filesQueued))

        catch case ex: Exception => ex.printStackTrace()

      new App().main(args)
      shutdown.await() // block until SIGTERM/SIGINT, then scope ends and forks are cancelled
