package perfectpair.domain.port.driven

import perfectpair.common.model.model.ID
import perfectpair.domain.model.{Account, Job, JobType}

trait JobRepo extends DomainRepo[Job, Job]:

  def findByJobTypeAndReferenceId(jobtype: JobType, referenceID: ID): Txn[Option[Job]]
