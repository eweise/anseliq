package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.{Job, JobType}

import java.time.OffsetDateTime

trait JobRepo extends DomainRepo[Job, Job]:

  def findByJobTypeAndReferenceId(jobtype: JobType, referenceID: ID): Txn[Vector[Job]]
  def findByJobType(jobtype:               JobType): Txn[Vector[Job]]
  def getNextJob(jobType:                  JobType): Txn[Option[Job]]

  /**
   * Moves a Submitted job to InProgress with a lease of `leaseSeconds` from the database's clock,
   * and counts the attempt. False if the job was no longer Submitted (another worker got there
   * first).
   */
  def claim(jobId: ID, leaseSeconds: Long, now: OffsetDateTime): Txn[Boolean]

  /**
   * Puts an InProgress job back in the queue, not claimable for `delaySeconds`, recording why its
   * last attempt failed. False if it's no longer InProgress.
   */
  def retryLater(jobId: ID, delaySeconds: Long, errorMessage: String, now: OffsetDateTime): Txn[Boolean]

  /**
   * Puts an InProgress job back in the queue, claimable now, and uncounts its attempt; false if it's
   * no longer InProgress.
   */
  def requeueAbandoned(jobId: ID, now: OffsetDateTime): Txn[Boolean]

  /**
   * Pushes an InProgress job's lease out to `leaseSeconds` from now; false if it's no longer
   * InProgress.
   */
  def extendLease(jobId: ID, leaseSeconds: Long): Txn[Boolean]

  /** InProgress jobs of this type whose lease has lapsed, locked for the caller to release. */
  def findExpiredLeases(jobType: JobType): Txn[Vector[Job]]
