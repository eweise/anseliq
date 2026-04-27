package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.common.model.model.ID
import perfectpair.domain.model.{Job, JobType}
import perfectpair.domain.port.driven.{JobRepo, Txn}

import java.util.UUID

class JobRepoLive extends Repo[Job, Job, UUID] with JobRepo:
  val j = Job.Table

  override def findByJobTypeAndReferenceId(jobtype: JobType, referenceID: ID): Txn[Option[Job]] =
    sql"""
        SELECT ${j.all}
        FROM $j AS j
        WHERE j.reference_id = $referenceID
        and j.job_type = $jobtype
      """.query[Job].run().headOption
