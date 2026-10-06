package anseliq.domain.service

import anseliq.common.model.*
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.model.JobStatus.{Complete, InProgress, Submitted}
import anseliq.domain.port.driven.{JobRepo, Txn}
import com.augustnagro.magnum.{DbCon, Transactor, transact}
import com.typesafe.scalalogging.StrictLogging
import org.postgresql.util.PSQLException
import ox.scheduling.{Schedule, repeat}

import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

class JobManager(jobRepo: JobRepo, clock: ClockService, dataSource: DataSource) extends StrictLogging:
  private val UniqueViolationSqlState = "23505"

  def submitAndProcessJob(jobType: JobType, referenceID: ID)(processFn: (job: Job) => JobInfo): Txn[Unit] =
    val jobId = submitJob(jobType, referenceID)
    val submittedJob = jobRepo
      .findById(jobId)
      .ErrIfNotFound("internal error. could not find job after submitting", jobId)

    val processedJob = submittedJob.copy(
      jobStatus = JobStatus.InProgress,
      updatedAt = Some(clock.now)
    )
    jobRepo.update(processedJob)

    try {
      val jobInfo = processFn(processedJob)
      completeJob(processedJob.id, jobInfo)
    } catch
      case ex: Exception =>
        ex.printStackTrace() // todo I think the txn is rolled back at this point :(
        markJobInError(processedJob.id, ex)

  // Idempotent: if an active (Submitted or InProgress) job already exists for this
  // (jobType, referenceID), return its id instead of creating a duplicate — covers the common,
  // non-concurrent causes of double-submission (event redelivery, a periodic submitter racing a
  // slow poller, a manual retrigger).
  //
  // The check-then-insert below still has a race window between two concurrent submitters: both
  // can see "no active job" and both attempt the insert. job_active_unique (a partial unique index
  // on job_type+reference_id where status is submitted/in_progress) is what actually prevents the
  // duplicate row; the loser's insert hits that constraint. In Postgres a failed statement poisons
  // the rest of the transaction until rolled back, so the insert is wrapped in a SAVEPOINT — on a
  // unique-violation we roll back to it (undoing just the failed insert, not the whole transaction)
  // and re-look-up the winner, so a racing caller gets the same "return the existing job" result as
  // the non-racing path instead of a raw constraint-violation exception.
  def submitJob(jobType: JobType, referenceID: ID): Txn[ID] =
    findActiveJob(jobType, referenceID) match
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
            findActiveJob(jobType, referenceID)
              .map(_.id)
              .getOrElse(throw ex)

  private def findActiveJob(jobType: JobType, referenceID: ID): Txn[Option[Job]] =
    jobRepo
      .findByJobTypeAndReferenceId(jobType, referenceID)
      .find(j => j.jobStatus == JobStatus.Submitted || j.jobStatus == JobStatus.InProgress)

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
            updatedAt    = Some(clock.now)
          )
        )
      )

  def pollForJob(jobType: JobType, pollingSeconds: Int)(fn: Job => Unit): Unit =
    repeat(Schedule.fixedInterval(pollingSeconds.seconds)) {
      try
        given transactor: Transactor = Transactor(dataSource)
        val jobOpt = transact(transactor) {
          getNextAvailableJob(jobType)
        }
        jobOpt.foreach(fn)
      catch
        case ex: Exception =>
          logger.error(s"Error polling for $jobType job, will retry in ${pollingSeconds}s", ex)
    }

  def getNextAvailableJob(jobType: JobType): Txn[Option[Job]] =
    val job = jobRepo.getNextJob(jobType)
    val results = job.map(job =>
      jobRepo.update(
        job.copy(
          jobStatus = InProgress,
          updatedAt = Some(clock.now)
        )
      )
      val found = jobRepo.findById(job.id).get
      found
    )
    results
