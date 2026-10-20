package anseliq.app

import anseliq.adapter.config.AppContainer
import anseliq.adapter.driving.*
import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.Txn
import com.augustnagro.magnum.*
import com.typesafe.scalalogging.Logger
import ox.*

import java.util.UUID
import scala.concurrent.duration.DurationInt

/**
 * Runs the background jobs and HTTP server against an AppContainer, the production wiring by
 * default. A caller that needs a different service (e.g. a test entry point that shouldn't hit real
 * S3) passes its own AppContainer overriding just that service.
 *
 * All background work is jobs: timers queue the periodic ones, each job type has one worker loop,
 * and a job that leads to more work queues that work as jobs itself:
 *
 * timer ─▶ CreateFileInventory ─▶ Ingestion (per new file) ─▶ Reconciliation (per Recon account)
 * timer ─▶ GenerateReconStats
 *
 * Events are only a record of what happened (the activity feed); nothing consumes them.
 */
class AppEnv(val app: AppContainer = new AppContainer {}):
  private val logger = Logger(getClass.getName)

  // Fixed reference ids for the singleton periodic jobs, so submitJob's dedupe keeps one queued.
  private val FileInventoryScanId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val ReconStatsScanId    = UUID.fromString("00000000-0000-0000-0000-000000000002")

  /** Starts every background job fork plus the HTTP server, and blocks until shutdown. */
  def run(args: Array[String]): Unit =
    val shutdownLatch = java.util.concurrent.CountDownLatch(1)

    Runtime.getRuntime.addShutdownHook(Thread(() => shutdownLatch.countDown()))

    supervised:
      fork(app.jobManager.submitEvery(JobType.CreateFileInventory, FileInventoryScanId, 1.minute))
      // Every minute rather than once every 24 hours because "due" depends on each fund flow's own
      // dailyCutoffHours, not on when the app happened to start — computeAndStoreDailySnapshots is
      // the cheap no-op once a fund flow's snapshot for today already exists.
      fork(app.jobManager.submitEvery(JobType.GenerateReconStats, ReconStatsScanId, 1.minute))

      fork(app.jobManager.runWorker(JobType.CreateFileInventory, 3)(_ => scanFileFeeds()))
      fork(app.jobManager.runWorker(JobType.Ingestion, 1)(ingest))
      fork(
        app.jobManager.runWorker(JobType.Reconciliation, 1)(job =>
          app.reconciliationRunner.runJob(job.referenceId, job.id)
        )
      )
      fork(app.jobManager.runWorker(JobType.GenerateReconStats, 3)(_ => generateReconStats()))

      HttpServer(app).main(args)
      shutdownLatch.await() // block until SIGTERM/SIGINT, then scope ends and forks are cancelled

  /** Finds new files on every feed and queues an Ingestion job for each, with its inventory row. */
  private def scanFileFeeds(): FileInventoryInfo =
    transact(Transactor(dataSource = app.dataSource)):
      val filesQueued = app.fileFeedRepo.findAll.map(scanFileFeed(_).size).sum
      FileInventoryInfo(filesQueued = filesQueued)

  /**
   * Finds the feed's new files and queues an Ingestion job for each, returning the job ids, then
   * checks whether the feed is overdue. New files are looked for first so a file that has just
   * landed counts toward the feed's cadence, rather than raising a FileMissing dispute that the
   * next scan resolves.
   */
  def scanFileFeed(feed: FileFeed): Txn[Vector[ID]] =
    val fileSource = app.fileSourceFactory.createFileSource(feed.dataSourceConfig().connectionConfig)
    val newFiles   = app.ingestor.detectAndQueue(feed.id, fileSource)
    val jobIds     = newFiles.map(file => app.jobManager.submitJob(JobType.Ingestion, file.id))
    logger.info(s"File inventory scan: queued ${newFiles.size} new files for feed ${feed.name}")
    app.ingestor.checkForMissingFile(feed)
    jobIds

  /**
   * Ingests the job's file, then queues reconciliation of the Recon accounts on its feed's specs.
   */
  def ingest(job: Job): IngestionInfo =
    val result = app.ingestor.ingestFileFromInventory(job.referenceId)
    // Every spec on the file's feed, not just the ones this run added rows to: a retry after a crash
    // resumes past the rows the failed attempt already committed, so it can't tell which specs those
    // went to, and their reconciliation would otherwise never be queued.
    transact(Transactor(dataSource = app.dataSource)):
      val inventory = app.fileInventoryRepo.findById(job.referenceId).ErrIfNotFound("FileInventory", job.referenceId)
      val specIds   = app.movementSpecRepo.findByFileFeedId(inventory.fileFeedId).map(_.id).toSet
      app.reconciliationRunner.queueReconciliations(specIds)
    IngestionInfo(
      numberOfRecordsIngested = result.recordCount,
      processingTimeSeconds   = result.ingestionTimeSeconds
    )

  /**
   * Computes and stores today's per-fund-flow analytics snapshot, and raises stale-mismatch
   * disputes.
   */
  private def generateReconStats(): ReconStatsInfo =
    transact(Transactor(dataSource = app.dataSource)):
      val beginTime = System.currentTimeMillis()
      val count     = app.reportingService.computeAndStoreDailySnapshots()

      val staleMismatchCount = app.reconSpecRepo.findAll.map(app.reconciler.raiseStaleMismatchDisputes).sum
      if staleMismatchCount > 0 then
        logger.info(s"raised $staleMismatchCount stale OneToMany AmountMismatch dispute(s)")

      ReconStatsInfo(
        fundFlowsSnapshotted  = count,
        processingTimeSeconds = (System.currentTimeMillis() - beginTime) / 1000
      )
