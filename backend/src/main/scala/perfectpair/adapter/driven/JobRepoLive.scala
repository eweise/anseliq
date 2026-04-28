package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.common.model.model.ID
import perfectpair.domain.model.{Job, JobStatus, JobType}
import perfectpair.domain.port.driven.{JobRepo, Txn}

import java.util.UUID

class JobRepoLive extends Repo[Job, Job, UUID] with JobRepo:
  val j = Job.Table

  override def findByJobTypeAndReferenceId(jobtype: JobType, referenceID: ID): Txn[Vector[Job]] =
    sql"""
        SELECT ${j.all}
        FROM $j AS j
        WHERE j.reference_id = $referenceID
        AND j.job_type = $jobtype
      """.query[Job].run()

  override def findByJobType(jobtype: JobType): Txn[Vector[Job]] =
    sql"""
        SELECT ${j.all}
        FROM $j AS j
        WHERE j.job_type = $jobtype
        ORDER BY created_at DESC
      """.query[Job].run()

  override def getNextJob(jobType: JobType): Txn[Option[Job]] =
    sql"""
        SELECT ${j.all}
        FROM $j AS j
        WHERE j.job_type = $jobType
        AND j.job_status = ${JobStatus.Submitted}
        ORDER BY created_at
        LIMIT 1
        FOR UPDATE SKIP LOCKED
      """.query[Job].run().headOption
