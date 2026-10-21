package anseliq.app

import anseliq.adapter.config.AppContainer
import anseliq.adapter.driven.DbMigrations
import anseliq.adapter.driving.*
import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.Txn
import com.augustnagro.magnum.*
import com.typesafe.scalalogging.Logger
import ox.*

import java.util.UUID
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.DurationInt
import scala.jdk.DurationConverters.*
import scala.util.control.NonFatal

/**
 * Runs the background jobs and HTTP server against an AppContainer, the production wiring by
 * default. A caller that needs a different service (e.g. a test entry point that shouldn't hit real
 * S3) passes its own AppContainer overriding just that service.
 *
 * All background work is jobs: timers queue the periodic ones, each job type has one worker loop,
 * and a job that leads to more work queues that work as jobs itself:
 *
 * timer ─▶ CreateFileInventory ─▶ Ingestion (per new file) ─▶ Reconciliation (per Recon account)
 *                                                               ─▶ SuggestMatches (if enabled)
 * timer ─▶ GenerateReconStats
 *
 * Events are only a record of what happened (the activity feed); nothing consumes them.
 */
class AppEnv(val app: AppContainer = new AppContainer {}):
  private val logger = Logger(getClass.getName)

  // Fixed reference ids for the singleton periodic jobs, so submitJob's dedupe keeps one queued.
  private val FileInventoryScanId = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val ReconStatsScanId    = UUID.fromString("00000000-0000-0000-0000-000000000002")

  private val services = app.config.getConfig("services")

  /**
   * Starts the services enabled under `services` in the config — the scheduler, each job type's
   * worker, and the HTTP server — and blocks until shutdown.
   */
  def run(args: Array[String]): Unit =
    // Fail now, not on the first login, if the HTTP server is missing its JWT secret.
    if services.getBoolean("http.enabled") then app.jwtSecret
    if app.config.getBoolean("db.migrate-on-start") then DbMigrations.migrate(app.config)
    if services.getBoolean("http.enabled") then ensureAdmin()
    val shutdownRequested = CountDownLatch(1)
    val stopped           = CountDownLatch(1)

    Runtime.getRuntime.addShutdownHook(Thread(() =>
      shutdownRequested.countDown()
      // The JVM halts as soon as its shutdown hooks return, so this one waits for the drain.
      stopped.await()
    ))

    // Not behind a services flag: every instance needs its probes, whatever else it runs.
    val healthServer = HealthServer(app.dataSource, services.getInt("health.port"))
    healthServer.start()

    try runServices(args, shutdownRequested)
    finally
      healthServer.stop()
      app.dataSource match
        case pool: AutoCloseable => pool.close()
        case _                   => ()
      stopped.countDown()

  private def runServices(args: Array[String], shutdownRequested: CountDownLatch): Unit =
    supervised:
      if services.getBoolean("scheduler.enabled") then
        logger.info("Starting scheduler")
        fork(app.jobManager.submitEvery(JobType.CreateFileInventory, FileInventoryScanId, 1.minute))
        // Every minute rather than once every 24 hours because "due" depends on each fund flow's own
        // dailyCutoffHours, not on when the app happened to start — computeAndStoreDailySnapshots is
        // the cheap no-op once a fund flow's snapshot for today already exists.
        fork(app.jobManager.submitEvery(JobType.GenerateReconStats, ReconStatsScanId, 1.minute))
      else logger.info("Scheduler disabled")

      val workers = Vector(
        startWorker(JobType.CreateFileInventory, "create-file-inventory")(_ => scanFileFeeds()),
        startWorker(JobType.Ingestion, "ingestion")(ingest),
        startWorker(JobType.Reconciliation, "reconciliation")(job =>
          app.reconciliationRunner.runJob(job.referenceId, job.id)
        ),
        startWorker(JobType.GenerateReconStats, "generate-recon-stats")(_ => generateReconStats()),
        startWorker(JobType.SuggestMatches, "suggest-matches")(job =>
          app.matchSuggestionService.suggestForAccount(job.referenceId)
        )
      ).flatten

      if services.getBoolean("http.enabled") then HttpServer(app).main(args)
      else logger.info("HTTP server disabled")
      shutdownRequested.await() // block until SIGTERM/SIGINT
      drainWorkers(workers)
      // the scope ends here, cancelling the scheduler

  /**
   * Creates the first Admin from admin.email/admin.password if there's no active one. A failure is
   * logged rather than stopping the app, which other users can still log in to: e.g. another
   * instance starting at the same moment created it first.
   */
  private def ensureAdmin(): Unit =
    def optional(path: String) = Option.when(app.config.hasPath(path))(app.config.getString(path))
    try
      (optional("admin.email"), optional("admin.password")) match
        case (Some(email), Some(password)) =>
          transact(app.transactor)(app.userService.createInitialAdmin(email, password))
            .foreach(admin => logger.info(s"Created the initial Admin, ${admin.email}"))
        case _ =>
          if !transact(app.transactor)(app.userService.hasActiveAdmin) then
            logger.warn("There's no active Admin: set ADMIN_EMAIL and ADMIN_PASSWORD to create one")
    catch
      case NonFatal(ex) =>
        if !transact(app.transactor)(app.userService.hasActiveAdmin) then
          logger.error("Couldn't create the initial Admin", ex)

  /** Forks jobType's worker loop if `services.workers.<key>` enables it. */
  private def startWorker(jobType: JobType, key: String)(handler: Job => JobInfo)(using
    Ox
  ): Option[CancellableFork[Unit]] =
    val worker = services.getConfig(s"workers.$key")
    if worker.getBoolean("enabled") then
      val pollingSeconds = worker.getInt("polling-seconds")
      logger.info(s"Starting $jobType worker, polling every ${pollingSeconds}s")
      Some(forkCancellable(app.jobManager.runWorker(jobType, pollingSeconds)(handler)))
    else
      logger.info(s"$jobType worker disabled")
      None

  /**
   * Stops the workers claiming jobs and waits up to the drain timeout for the ones they're running
   * to finish; any still running then are interrupted and put back in the queue.
   */
  private def drainWorkers(workers: Vector[CancellableFork[Unit]]): Unit =
    val drainTimeout = services.getDuration("shutdown.drain-timeout").toScala
    logger.info(s"Shutting down: waiting up to $drainTimeout for running jobs to finish")
    app.jobManager.stopClaiming()
    timeoutOption(drainTimeout)(workers.foreach(_.join())) match
      case Some(_) => logger.info("Running jobs finished")
      case None =>
        logger.warn(s"Jobs still running after $drainTimeout, requeueing them")
        app.jobManager.abandonRunningJobs(() => workers.foreach(_.cancel()))

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
