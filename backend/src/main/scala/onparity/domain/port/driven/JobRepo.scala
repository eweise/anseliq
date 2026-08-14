package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.{Job, JobType}

trait JobRepo extends DomainRepo[Job, Job]:

  def findByJobTypeAndReferenceId(jobtype: JobType, referenceID: ID): Txn[Vector[Job]]
  def findByJobType(jobtype:               JobType): Txn[Vector[Job]]
  def getNextJob(jobType:                  JobType): Txn[Option[Job]]
