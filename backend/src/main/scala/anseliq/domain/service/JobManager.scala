package anseliq.domain.service

import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.model.JobStatus.{Complete, InProgress, Submitted}
import anseliq.domain.port.driven.{JobRepo, Txn}
import anseliq.util.LogContext
import com.augustnagro.magnum.{DbCon, Transactor, transact}
import com.typesafe.scalalogging.StrictLogging
import org.postgresql.util.PSQLException
import ox.scheduling.{Schedule, repeat}

import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.control.NonFatal

// A claimed (InProgress) job holds a lease that withLease's heartbeat keeps renewing while the work
// runs. If the worker dies, the lease lapses and the next poll for that job type requeues the job,
// rather than leaving it InProgress for good and blocking that (jobType, referenceID) forever.
//
// A failed attempt, or an abandoned one, is retried after a backoff (retryBaseDelay, doubling each
// attempt) until maxAttempts, then the job is marked Error.
class JobManager(
  jobRepo:        JobRepo,
  clock:          ClockService,
  dataSource:     DataSource,
  leaseDuration:  FiniteDuration = 5.minutes,
  maxAttempts:    Int            = 5,
  retryBaseDelay: FiniteDuration = 30.seconds
) extends StrictLogging:
  private val UniqueViolationSqlState = "23505"
  private val MaxRetryDelay           = 30.minutes
  private val leaseSeconds            = leaseDuration.toSeconds
  private val heartbeatInterval       = leaseDuration / 3

  def submitAndProcessJob(jobType: JobType, referenceID: ID)(processFn: (job: Job) => JobInfo): Txn[Unit] =
    val jobId = submitJob(jobType, referenceID)
    claimJob(jobId)
    val processedJob = jobRepo
      .findById(jobId)
      .ErrIfNotFound("internal error. could not find job after submitting", jobId)

    try {
      val jobInfo = processFn(processedJob)
      completeJob(processedJob.id, jobInfo)
    } catch
      case ex: Exception =>
        ex.printStackTrace() // todo I think the txn is rolled back at this point :(
        markJobInError(processedJob.id, ex)

  // Idempotent: if a job is already queued (Submitted) for this (jobType, referenceID), return its
  // id instead of creating a duplicate — covers the common, non-concurrent causes of
  // double-submission (event redelivery, a periodic submitter racing a slow poller, a manual
  // retrigger). A job that's already InProgress doesn't count: it may have read its inputs before
  // whatever prompted this submission, so a new job is queued to run once it finishes (getNextJob
  // holds it back until then).
  //
  // The check-then-insert below still has a race window between two concurrent submitters: both
  // can see "nothing queued" and both attempt the insert. job_submitted_unique (a partial unique
  // index on job_type+reference_id where status is submitted) is what actually prevents the
  // duplicate row; the loser's insert hits that constraint. In Postgres a failed statement poisons
  // the rest of the transaction until rolled back, so the insert is wrapped in a SAVEPOINT — on a
  // unique-violation we roll back to it (undoing just the failed insert, not the whole transaction)
  // and re-look-up the winner, so a racing caller gets the same "return the existing job" result as
  // the non-racing path instead of a raw constraint-violation exception.
  def submitJob(jobType: JobType, referenceID: ID): Txn[ID] =
    findQueuedJob(jobType, referenceID) match
      case Some(existing) =>
        logger.debug(
          s"submitJob: active $jobType job already exists for referenceID=$referenceID, skipping duplicate submission (existing job ${existing.id})"
        )
        existing.id
      case None =>
        val submittedJob = Job(
          jobType     = jobType,
          referenceId = referenceID,
          jobStatus   = Submitted
        )
        val connection = summon[DbCon].connection
        val savepoint  = connection.setSavepoint()
        try
          jobRepo.insert(submittedJob)
          submittedJob.id
        catch
          // Magnum wraps the driver's PSQLException in its own SqlException, so the unique
          // violation has to be found by walking the cause chain rather than matching the
          // exception's own type.
          case ex: Exception if isUniqueViolation(ex) =>
            connection.rollback(savepoint)
            logger.debug(
              s"submitJob: lost the race inserting $jobType job for referenceID=$referenceID, using the winner's job instead"
            )
            findQueuedJob(jobType, referenceID)
              .map(_.id)
              .getOrElse(throw ex)

  private def findQueuedJob(jobType: JobType, referenceID: ID): Txn[Option[Job]] =
    jobRepo
      .findByJobTypeAndReferenceId(jobType, referenceID)
      .find(_.jobStatus == JobStatus.Submitted)

  /**
   * Claims a Submitted job for the caller to run now. An IllegalStateError if another worker
   * already claimed it, or another job for the same (jobType, referenceID) is running.
   */
  def claimJob(jobId: ID): Txn[Unit] =
    val claimed =
      try jobRepo.claim(jobId, leaseSeconds, clock.now)
      catch
        // job_in_progress_unique: another job for the same (jobType, referenceID) is running.
        case ex: Exception if isUniqueViolation(ex) => false
    if !claimed then throw IllegalStateError(s"job $jobId is already running, or another job for the same target is")

  private def isUniqueViolation(ex: Throwable): Boolean =
    ex match
      case null => false
      case p: PSQLException => p.getSQLState == UniqueViolationSqlState
      case _ => isUniqueViolation(ex.getCause)

  def completeJob(jobId: ID, jobInfo: JobInfo): Txn[Unit] =
    // validate that the right type of job info is associate its job type

    jobRepo
      .findById(jobId)
      .map(job =>
        if job.jobStatus != InProgress then
          throw IllegalStateError(
            s" cannot move job with referenceID: $jobId to status ${JobStatus.Complete}. Job currently in status ${job.jobStatus}"
          )

        job.jobType match
          case JobType.Reconciliation =>
            if !jobInfo.isInstanceOf[ReconciliationInfo] then
              throw IllegalArgumentError(job.jobType.toString, "wrong jobInfo associated with job type")

          case JobType.Ingestion =>
            if !jobInfo.isInstanceOf[IngestionInfo] then
              throw IllegalArgumentError(job.jobType.toString, "wrong jobInfo associated with job type")

          case JobType.CreateFileInventory =>
            if !jobInfo.isInstanceOf[FileInventoryInfo] then
              throw IllegalArgumentError(job.jobType.toString, "wrong jobInfo associated with job type")

          case JobType.GenerateReconStats =>
            if !jobInfo.isInstanceOf[ReconStatsInfo] then
              throw IllegalArgumentError(job.jobType.toString, "wrong jobInfo associated with job type")

        jobRepo.update(
          job.copy(
            jobStatus   = Complete,
            jobInfoJson = Some(upickle.write(jobInfo)),
            lockedUntil = None,
            updatedAt   = Some(clock.now)
          )
        )
      ) match
      case None => throw NotFoundError(s"Could not find job with jobID: $jobId")
      case _    => ()

  def markJobInError(jobId: ID, ex: Exception): Txn[Unit] =
    jobRepo
      .findById(jobId)
      .map(job =>
        if job.jobStatus != InProgress then
          throw IllegalStateError(
            s" cannot move job with jobID: $jobId to status ${JobStatus.Error}. Job currently in status ${job.jobStatus}"
          )
        jobRepo.update(
          job.copy(
            jobStatus    = JobStatus.Error,
            errorMessage = Some(ex.getMessage),
            lockedUntil  = None,
            updatedAt    = Some(clock.now)
          )
        )
      )

  /**
   * Runs jobType's worker loop: every pollingSeconds, claims the next queued job and runs `handler`
   * on it under a lease. The JobInfo it returns completes the job; an exception fails the attempt
   * (see retryOrFail). Work that should follow is queued as jobs by the handler itself.
   */
  def runWorker(jobType: JobType, pollingSeconds: Int)(handler: Job => JobInfo): Unit =
    val transactor = Transactor(dataSource)
    pollForJob(jobType, pollingSeconds): job =>
      LogContext.withContext("job" -> jobType, "job_id" -> job.id):
        try
          val jobInfo = handler(job)
          transact(transactor)(completeJob(job.id, jobInfo))
        catch
          case ex: Exception =>
            logger.error(s"$jobType job failed", ex)
            transact(transactor)(retryOrFail(job.id, ex))

  /**
   * Submits a singleton job (one fixed referenceID) every `interval`; submitJob keeps at most one
   * queued.
   */
  def submitEvery(jobType: JobType, referenceID: ID, interval: FiniteDuration): Unit =
    val transactor = Transactor(dataSource)
    repeat(Schedule.fixedInterval(interval)) {
      try
        transact(transactor)(submitJob(jobType, referenceID))
        ()
      catch
        case ex: Exception =>
          logger.error(s"Error submitting $jobType job, will retry in $interval", ex)
    }

  def pollForJob(jobType: JobType, pollingSeconds: Int)(fn: Job => Unit): Unit =
    val transactor = Transactor(dataSource)
    repeat(Schedule.fixedInterval(pollingSeconds.seconds)) {
      try
        transact(transactor)(releaseExpiredLeases(jobType))
        val jobOpt = transact(transactor)(getNextAvailableJob(jobType))
        jobOpt.foreach(job => withLease(job.id)(fn(job)))
      catch
        case ex: Exception =>
          logger.error(s"Error polling for $jobType job, will retry in ${pollingSeconds}s", ex)
    }

  def getNextAvailableJob(jobType: JobType): Txn[Option[Job]] =
    jobRepo
      .getNextJob(jobType)
      .map: job =>
        claimJob(job.id)
        jobRepo.findById(job.id).get

  /**
   * Runs `body` while a heartbeat keeps the claimed job's lease from lapsing. The heartbeat runs on
   * its own virtual thread so `body` stays on the caller's (and keeps its logging context).
   */
  def withLease[T](jobId: ID)(body: => T): T =
    val transactor = Transactor(dataSource)
    val stopped    = AtomicBoolean(false)
    val heartbeat = Thread
      .ofVirtual()
      .start(() =>
        while !stopped.get() do
          try
            Thread.sleep(heartbeatInterval.toMillis)
            if !stopped.get() && !transact(transactor)(jobRepo.extendLease(jobId, leaseSeconds)) then
              logger.warn(s"job $jobId is no longer InProgress, so its lease can't be extended")
          catch
            case _: InterruptedException => ()
            case NonFatal(ex) if !stopped.get() =>
              logger.warn(s"failed to extend lease for job $jobId, will retry", ex)
            case NonFatal(_) => ()
      )
    try body
    finally
      stopped.set(true)
      heartbeat.interrupt()

  /**
   * Fails the claimed job's current attempt. A NonRetryableError marks it Error; anything else is
   * retried after a backoff, unless it's out of attempts or a newer job for the same target is
   * already queued (see requeueOrFail).
   */
  def retryOrFail(jobId: ID, ex: Exception): Txn[Unit] =
    val job = jobRepo.findById(jobId).getOrElse(throw NotFoundError(s"Could not find job with jobID: $jobId"))
    if job.jobStatus != InProgress then
      throw IllegalStateError(
        s" cannot fail an attempt of job with jobID: $jobId. Job currently in status ${job.jobStatus}"
      )
    ex match
      case _: NonRetryableError => markJobInError(jobId, ex)
      case _ => requeueOrFail(job, ex.getMessage)

  /**
   * Releases InProgress jobs whose worker stopped renewing its lease (crashed, or the instance went
   * away). Each is retried under the same id, so a resumable job picks up from its progressJson
   * checkpoint — counted as an attempt, so a job that takes the process down every time still gives
   * up eventually.
   */
  def releaseExpiredLeases(jobType: JobType): Txn[Unit] =
    jobRepo
      .findExpiredLeases(jobType)
      .foreach(job => requeueOrFail(job, "Worker lease expired"))

  // Retries an InProgress job after a backoff, or marks it Error if it's out of attempts. Also Error
  // if a newer job for the same target is already queued: that one redoes the work anyway, and
  // job_submitted_unique wouldn't let this one back in the queue beside it.
  private def requeueOrFail(job: Job, reason: String): Txn[Unit] =
    def fail(message: String): Unit =
      logger.warn(s"${job.jobType} job ${job.id} failed: $message")
      jobRepo.update(
        job.copy(
          jobStatus    = JobStatus.Error,
          errorMessage = Some(message),
          lockedUntil  = None,
          updatedAt    = Some(clock.now)
        )
      )

    findQueuedJob(job.jobType, job.referenceId) match
      case Some(queued)                        => fail(s"$reason; superseded by job ${queued.id}")
      case None if job.attempts >= maxAttempts => fail(s"$reason; gave up after ${job.attempts} attempts")
      case None =>
        val delay = retryDelay(job.attempts)
        logger.warn(s"${job.jobType} job ${job.id} failed attempt ${job.attempts} ($reason), retrying in $delay")
        if !jobRepo.retryLater(job.id, delay.toSeconds, reason, clock.now) then
          logger.warn(s"${job.jobType} job ${job.id} was no longer InProgress, so it wasn't requeued")

  // retryBaseDelay after the first attempt, doubling after each one after that, capped at MaxRetryDelay.
  private def retryDelay(attempts: Int): FiniteDuration =
    (retryBaseDelay * (1L << (attempts - 1).max(0).min(20))).min(MaxRetryDelay)
