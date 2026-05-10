package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import ox.scheduling.{Schedule, repeat}
import perfectpair.common.model.model.ID
import perfectpair.common.model.{IngestionInfo, JobInfo, ReconciliationInfo}
import perfectpair.domain.model.*
import perfectpair.domain.model.JobStatus.{Complete, InProgress, Submitted}
import perfectpair.domain.port.driven.{JobRepo, Txn}

import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

class JobManager(jobRepo: JobRepo, clock: ClockService)(using
  dataSource: DataSource
):

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

  // todo need to handle idempotency (in all services actually)
  def submitJob(jobType: JobType, referenceID: ID): Txn[ID] =
    val submittedJob = Job(
      jobType     = jobType,
      referenceId = referenceID,
      jobStatus   = Submitted
    )
    jobRepo.insert(submittedJob)
    submittedJob.id

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

  def getNextAvailableJob(jobType: JobType): Txn[Option[Job]] =
    jobRepo
      .getNextJob(jobType)
      .map(job =>
        jobRepo.update(
          job.copy(
            jobStatus = InProgress,
            updatedAt = Some(clock.now)
          )
        )
        job
      )

  def pollForJob(jobType: JobType, pollingSeconds: Int)(fn: Job => Unit): Unit =
    repeat(Schedule.fixedInterval(pollingSeconds.seconds).maxAttempts(3)) {
      given transactor: Transactor = Transactor(dataSource)
      transact(transactor) {
        getNextAvailableJob(jobType).foreach { job =>
          fn(job)
        }
      }
    }
