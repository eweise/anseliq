package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model.{Job, JobType}

trait JobRepo extends DomainRepo[Job, Job]:

  def findByJobTypeAndReferenceId(jobtype: JobType, referenceID: ID): Txn[Option[Job]]
  def getNextJob(jobType:                  JobType): Txn[Option[Job]]
