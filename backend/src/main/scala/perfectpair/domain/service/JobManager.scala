package perfectpair.domain.service

import perfectpair.common.model.model.ID
import perfectpair.domain.model.*
import perfectpair.domain.model.JobStatus.{Complete, InProgress, Submitted}
import perfectpair.domain.port.driven.{JobRepo, Txn}

import java.time.OffsetDateTime

class JobManager(
  jobRepo: JobRepo
):
  // todo need to handle idempotency (in all services actually)
  def submitJob(jobType: JobType, referenceID: ID): Txn[Unit] =
    val submittedJob = Job(
      jobType     = jobType,
      referenceId = referenceID,
      jobStatus   = Submitted
    )
    jobRepo.insert(submittedJob)

  def getNextAvailableJob(jobType: JobType): Txn[Option[Job]] =
    jobRepo
      .getNextJob(jobType)
      .map(job =>
        jobRepo.update(
          job.copy(
            jobStatus = InProgress,
            updatedAt = Some(OffsetDateTime.now())
          )
        )
        job
      )

  def completeJob(jobType: JobType, referenceID: ID): Txn[Unit] =
    jobRepo
      .findByJobTypeAndReferenceId(jobType, referenceID)
      .map(job =>
        if job.jobStatus != InProgress then
          throw IllegalStateError(
            s" cannot move job with referenceID: $referenceID to status ${JobStatus.Complete}. Job currently in status ${job.jobStatus}"
          )
        jobRepo.update(
          job.copy(
            jobStatus = Complete,
            updatedAt = Some(OffsetDateTime.now())
          )
        )
      ) match
      case None => throw NotFoundError(s"Could not find job with referenceID: $referenceID")
      case _    => ()

  def markJobInError(jobType: JobType, referenceID: ID, ex: Exception): Txn[Unit] =
    jobRepo
      .findByJobTypeAndReferenceId(jobType, referenceID)
      .map(job =>
        if job.jobStatus != InProgress then
          throw IllegalStateError(
            s" cannot move job with referenceID: $referenceID to status ${JobStatus.Error}. Job currently in status ${job.jobStatus}"
          )
        jobRepo.update(
          job.copy(
            jobStatus    = JobStatus.Error,
            errorMessage = Some(ex.getMessage),
            updatedAt    = Some(OffsetDateTime.now())
          )
        )
      )
