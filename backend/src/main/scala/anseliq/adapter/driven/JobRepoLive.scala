package anseliq.adapter.driven

import anseliq.domain.service.ClockService
import anseliq.common.model.model.ID
import anseliq.domain.model.{Job, JobStatus, JobType}
import anseliq.domain.port.driven.{JobRepo, Txn}
import com.augustnagro.magnum.sql

import java.time.OffsetDateTime
import java.util.UUID

class JobRepoLive(clock: ClockService) extends AuditedRepo[Job, UUID](clock) with JobRepo:
  val j = Job.Table

  override def findByJobTypeAndReferenceId(jobtype: JobType, referenceId: ID): Txn[Vector[Job]] =
    sql"""
        SELECT ${j.all}
        FROM $j AS j
        WHERE j.reference_id = $referenceId
        AND j.job_type = $jobtype
      """.query[Job].run()

  override def findByJobType(jobtype: JobType): Txn[Vector[Job]] =
    sql"""
        SELECT ${j.all}
        FROM $j AS j
        WHERE j.job_type = $jobtype
        ORDER BY created_at DESC
      """.query[Job].run()

  // Skips a job that's backing off before a retry, and one whose (job_type, reference_id) already
  // has one running: it waits as the follow-up run until that one finishes, rather than running
  // alongside it.
  override def getNextJob(jobType: JobType): Txn[Option[Job]] =
    sql"""
        SELECT ${j.all}
        FROM $j AS j
        WHERE j.job_type = $jobType
        AND j.job_status = ${JobStatus.Submitted}
        AND (j.run_at IS NULL OR j.run_at <= now())
        AND NOT EXISTS (
          SELECT 1 FROM $j AS running
          WHERE running.job_type = j.job_type
          AND running.reference_id = j.reference_id
          AND running.job_status = ${JobStatus.InProgress}
        )
        ORDER BY created_at
        LIMIT 1
        FOR UPDATE OF j SKIP LOCKED
      """.query[Job].run().headOption

  // Lease times come from the database's clock, not ClockService: every instance has to agree on
  // when a lease lapses, and the test clock can be moved forward hours at a time.
  override def claim(jobId: ID, leaseSeconds: Long, now: OffsetDateTime): Txn[Boolean] =
    sql"""
        UPDATE $j
        SET job_status = ${JobStatus.InProgress},
            locked_until = now() + interval '1 second' * $leaseSeconds,
            attempts = attempts + 1,
            updated_at = $now
        WHERE id = $jobId
        AND job_status = ${JobStatus.Submitted}
      """.update.run() == 1

  override def retryLater(jobId: ID, delaySeconds: Long, errorMessage: String, now: OffsetDateTime): Txn[Boolean] =
    sql"""
        UPDATE $j
        SET job_status = ${JobStatus.Submitted},
            run_at = now() + interval '1 second' * $delaySeconds,
            locked_until = NULL,
            error_message = $errorMessage,
            updated_at = $now
        WHERE id = $jobId
        AND job_status = ${JobStatus.InProgress}
      """.update.run() == 1

  override def requeueAbandoned(jobId: ID, now: OffsetDateTime): Txn[Boolean] =
    sql"""
        UPDATE $j
        SET job_status = ${JobStatus.Submitted},
            run_at = NULL,
            locked_until = NULL,
            attempts = attempts - 1,
            updated_at = $now
        WHERE id = $jobId
        AND job_status = ${JobStatus.InProgress}
      """.update.run() == 1

  override def extendLease(jobId: ID, leaseSeconds: Long): Txn[Boolean] =
    sql"""
        UPDATE $j
        SET locked_until = now() + interval '1 second' * $leaseSeconds
        WHERE id = $jobId
        AND job_status = ${JobStatus.InProgress}
      """.update.run() == 1

  // A null lease means the job was claimed before leases existed, so nothing is renewing it either.
  override def findExpiredLeases(jobType: JobType): Txn[Vector[Job]] =
    sql"""
        SELECT ${j.all}
        FROM $j AS j
        WHERE j.job_type = $jobType
        AND j.job_status = ${JobStatus.InProgress}
        AND (j.locked_until IS NULL OR j.locked_until < now())
        FOR UPDATE SKIP LOCKED
      """.query[Job].run()
