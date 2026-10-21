package anseliq.domain.service

import anseliq.common.model.ReconciliationInfo
import anseliq.common.model.model.ID
import anseliq.domain.model.JobStatus.{InProgress, Submitted}
import anseliq.domain.model.JobType.Reconciliation
import anseliq.domain.model.{IllegalArgumentError, IllegalStateError, Job, JobStatus, JobType}
import anseliq.domain.port.driven.Txn
import anseliq.testing
import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.{sql, transact}
import ox.*

import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, CyclicBarrier}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.util.Try

class JobManagerSuite extends munit.FunSuite:
  private val app        = TestAppContainer()
  private val transactor = app.transactor
  private val clock      = app.clock
  private val jobRepo    = app.jobRepo
  private val jobManager = app.jobManager

  private class HeldClaim(thread: Thread, commit: CountDownLatch, failure: AtomicReference[Throwable]):
    def release(): Unit =
      commit.countDown()
      thread.join()
      Option(failure.get).foreach(throw _)

  // Claims the job on its own thread and holds that transaction open, row lock and all, until release().
  private def holdingClaimUntilReleased(jobId: ID): HeldClaim =
    val claimed = CountDownLatch(1)
    val commit  = CountDownLatch(1)
    val failure = AtomicReference[Throwable]()
    val thread = Thread
      .ofPlatform()
      .start(() =>
        try
          transact(transactor):
            jobManager.claimJob(jobId)
            claimed.countDown()
            commit.await()
        catch case ex: Throwable => failure.set(ex)
        finally claimed.countDown()
      )
    claimed.await()
    Option(failure.get).foreach(throw _)
    HeldClaim(thread, commit, failure)

  // Waits until some other connection's claim UPDATE is waiting on a row lock, so the test exercises
  // the blocked-then-recheck path rather than a claim that simply runs after the first commits.
  private def awaitClaimBlockedOnRowLock(): Unit =
    val deadline = System.currentTimeMillis() + 5000
    def blocked = transact(transactor):
      sql"""
          SELECT count(*) FROM pg_stat_activity
          WHERE wait_event_type = 'Lock' AND query ILIKE '%UPDATE%locked_until%'
        """.query[Long].run().head > 0
    while !blocked do
      assert(System.currentTimeMillis() < deadline, "the second claim never blocked on the first's row lock")
      Thread.sleep(20)

  // Claims queued jobs of this type, oldest first, until it reaches this one; None if it isn't
  // claimable. Other, real jobs can be queued ahead of a test's own on this shared dev database, so
  // the next available job can't be assumed to be the test's. Only for use inside rollBackOnly.
  private def claimNextUntil(jobType: JobType, jobId: ID): Txn[Option[Job]] =
    Iterator
      .continually(jobManager.getNextAvailableJob(jobType))
      .takeWhile(_.isDefined)
      .flatten
      .find(_.id == jobId)

  private def reconInfo(referenceID: UUID) =
    ReconciliationInfo(
      accountId                   = referenceID.toString,
      accountName                 = "test",
      reconciliationsCreatedCount = 1,
      processingTimeSeconds       = 1
    )

  // As if the worker died an hour ago: its heartbeat stopped renewing the lease.
  private def expireLease(jobId: ID, progressJson: Option[String] = None): Txn[Unit] =
    val job = jobRepo.findById(jobId).get
    jobRepo.update(job.copy(lockedUntil = Some(OffsetDateTime.now().minusHours(1)), progressJson = progressJson))

  test("submit and complete job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val jobId       = jobManager.submitJob(Reconciliation, referenceID)
        val foundJob    = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID)
        assert(foundJob.nonEmpty)

        claimNextUntil(Reconciliation, jobId).get
        jobManager.completeJob(
          jobId,
          ReconciliationInfo(
            accountId                   = UUID.randomUUID().toString,
            accountName                 = "test",
            reconciliationsCreatedCount = 1,
            processingTimeSeconds       = 1
          )
        )
        assertEquals(claimNextUntil(Reconciliation, jobId), None)

  test("submit and create error job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val jobId       = jobManager.submitJob(Reconciliation, referenceID)
        claimNextUntil(Reconciliation, jobId).get
        jobManager.markJobInError(jobId, new IllegalStateError("boom"))
        assertEquals(claimNextUntil(Reconciliation, jobId), None)
        val foundErrorJob = jobRepo.findById(jobId).get
        assertEquals(foundErrorJob.errorMessage.get, "boom")

  test(
    "submitJob is idempotent: a second submission while a job is still Submitted returns the existing job, not a duplicate"
  ):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val firstJobId  = jobManager.submitJob(Reconciliation, referenceID)
        val secondJobId = jobManager.submitJob(Reconciliation, referenceID)

        assertEquals(secondJobId, firstJobId)
        assertEquals(jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).size, 1)

  test(
    "submitJob while a job is InProgress queues one follow-up job, which waits until the running one finishes"
  ):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val firstJobId  = jobManager.submitJob(Reconciliation, referenceID)
        jobManager.claimJob(firstJobId)

        val followUpId = jobManager.submitJob(Reconciliation, referenceID)
        assert(followUpId != firstJobId, "work arriving mid-run must not be coalesced into the running job")
        assertEquals(jobManager.submitJob(Reconciliation, referenceID), followUpId)
        assertEquals(jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).size, 2)

        assertEquals(
          claimNextUntil(Reconciliation, followUpId),
          None,
          "the follow-up must not run alongside the running job"
        )

        jobManager.completeJob(firstJobId, reconInfo(referenceID))
        assertEquals(claimNextUntil(Reconciliation, followUpId).map(_.id), Some(followUpId))

  test("claimJob refuses a job while another for the same referenceId is running"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        jobManager.claimJob(jobManager.submitJob(Reconciliation, referenceID))
        val followUpId = jobManager.submitJob(Reconciliation, referenceID)

        intercept[IllegalStateError](jobManager.claimJob(followUpId))

  test("claimJob refuses a job that's already been claimed"):
    transact(transactor):
      testing.rollBackOnly:
        val jobId = jobManager.submitJob(Reconciliation, UUID.randomUUID())
        jobManager.claimJob(jobId)
        assert(jobRepo.findById(jobId).get.lockedUntil.isDefined)

        intercept[IllegalStateError](jobManager.claimJob(jobId))

  test("releaseExpiredLeases requeues a job whose lease lapsed, keeping its id and progress"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val jobId       = jobManager.submitJob(Reconciliation, referenceID)
        jobManager.claimJob(jobId)
        expireLease(jobId, progressJson = Some("""{"checkpoint":1}"""))

        jobManager.releaseExpiredLeases(Reconciliation)

        val released = jobRepo.findById(jobId).get
        assertEquals(released.jobStatus, Submitted)
        assertEquals(released.lockedUntil, None)
        assertEquals(released.progressJson, Some("""{"checkpoint":1}"""))

  test("releaseExpiredLeases errors an abandoned job when a newer one for the same target is already queued"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val jobId       = jobManager.submitJob(Reconciliation, referenceID)
        jobManager.claimJob(jobId)
        val followUpId = jobManager.submitJob(Reconciliation, referenceID)
        expireLease(jobId)

        jobManager.releaseExpiredLeases(Reconciliation)

        val abandoned = jobRepo.findById(jobId).get
        assertEquals(abandoned.jobStatus, JobStatus.Error)
        assert(abandoned.errorMessage.exists(_.contains(followUpId.toString)), abandoned.errorMessage)
        assertEquals(jobRepo.findById(followUpId).get.jobStatus, Submitted)

  test("releaseExpiredLeases leaves a job with a live lease alone"):
    transact(transactor):
      testing.rollBackOnly:
        val jobId = jobManager.submitJob(Reconciliation, UUID.randomUUID())
        jobManager.claimJob(jobId)

        jobManager.releaseExpiredLeases(Reconciliation)

        assertEquals(jobRepo.findById(jobId).get.jobStatus, InProgress)

  test("retryOrFail puts a failed job back in the queue, held back by a backoff, with the failure recorded"):
    transact(transactor):
      testing.rollBackOnly:
        val jobId = jobManager.submitJob(Reconciliation, UUID.randomUUID())
        jobManager.claimJob(jobId)

        jobManager.retryOrFail(jobId, RuntimeException("connection reset"))

        val retried = jobRepo.findById(jobId).get
        assertEquals(retried.jobStatus, Submitted)
        assertEquals(retried.attempts, 1)
        assertEquals(retried.errorMessage, Some("connection reset"))
        assert(retried.runAt.isDefined, "a retry must wait out its backoff")
        assertEquals(claimNextUntil(Reconciliation, jobId), None, "a job backing off must not be claimable yet")

  test("getNextAvailableJob claims a retried job once its backoff has passed, counting the attempt"):
    transact(transactor):
      testing.rollBackOnly:
        val jobId = jobManager.submitJob(Reconciliation, UUID.randomUUID())
        jobManager.claimJob(jobId)
        jobManager.retryOrFail(jobId, RuntimeException("connection reset"))
        val retried = jobRepo.findById(jobId).get
        jobRepo.update(retried.copy(runAt = Some(OffsetDateTime.now().minusMinutes(1))))

        assertEquals(claimNextUntil(Reconciliation, jobId).map(_.attempts), Some(2))

  test("retryOrFail marks the job Error straight away for a NonRetryableError"):
    transact(transactor):
      testing.rollBackOnly:
        val jobId = jobManager.submitJob(Reconciliation, UUID.randomUUID())
        jobManager.claimJob(jobId)

        jobManager.retryOrFail(jobId, IllegalStateError("bad config"))

        val failed = jobRepo.findById(jobId).get
        assertEquals(failed.jobStatus, JobStatus.Error)
        assertEquals(failed.errorMessage, Some("bad config"))

  test("retryOrFail marks the job Error once it's out of attempts"):
    val oneAttemptManager = JobManager(jobRepo, clock, app.dataSource, maxAttempts = 1)
    transact(transactor):
      testing.rollBackOnly:
        val jobId = oneAttemptManager.submitJob(Reconciliation, UUID.randomUUID())
        oneAttemptManager.claimJob(jobId)

        oneAttemptManager.retryOrFail(jobId, RuntimeException("connection reset"))

        val failed = jobRepo.findById(jobId).get
        assertEquals(failed.jobStatus, JobStatus.Error)
        assert(failed.errorMessage.exists(_.contains("gave up after 1 attempts")), failed.errorMessage)

  test("releaseExpiredLeases gives up on a job that keeps losing its lease, once it's out of attempts"):
    val oneAttemptManager = JobManager(jobRepo, clock, app.dataSource, maxAttempts = 1)
    transact(transactor):
      testing.rollBackOnly:
        val jobId = oneAttemptManager.submitJob(Reconciliation, UUID.randomUUID())
        oneAttemptManager.claimJob(jobId)
        expireLease(jobId)

        oneAttemptManager.releaseExpiredLeases(Reconciliation)

        assertEquals(jobRepo.findById(jobId).get.jobStatus, JobStatus.Error)

  test(
    "claimJob under real concurrency: a claim blocked behind another's uncommitted claim fails once that one commits"
  ):
    // Not rolled back — needs two separate connections. The first claim holds the row (as a poller
    // would between claiming and committing); the second claim's UPDATE blocks on that row lock,
    // and once the first commits, Postgres re-checks `job_status = 'submitted'` against the new
    // row version, matches nothing, and the second claimer must get an IllegalStateError.
    val jobId = transact(transactor)(jobManager.submitJob(Reconciliation, UUID.randomUUID()))
    try
      val secondResult = AtomicReference[Try[Unit]]()
      val first        = holdingClaimUntilReleased(jobId)
      val second =
        Thread.ofPlatform().start(() => secondResult.set(Try(transact(transactor)(jobManager.claimJob(jobId)))))
      awaitClaimBlockedOnRowLock()
      first.release()
      second.join()

      assert(
        secondResult.get.failed.toOption.exists(_.isInstanceOf[IllegalStateError]),
        s"the second claimer must be refused, got $secondResult"
      )
      assertEquals(transact(transactor)(jobRepo.findById(jobId).get.jobStatus), InProgress)
    finally transact(transactor)(jobRepo.deleteById(jobId))

  test("getNextAvailableJob under real concurrency: a poller skips a job another connection is claiming"):
    val jobId = transact(transactor)(jobManager.submitJob(Reconciliation, UUID.randomUUID()))
    try
      val first = holdingClaimUntilReleased(jobId)
      // Rolled back: the poller may claim some other queued Reconciliation job on this shared dev DB.
      val polled = transact(transactor)(testing.rollBackOnly(jobManager.getNextAvailableJob(Reconciliation)))
      first.release()

      assert(!polled.exists(_.id == jobId), "the poller must not claim a job another connection holds")
      assertEquals(transact(transactor)(jobRepo.findById(jobId).get.jobStatus), InProgress)
    finally transact(transactor)(jobRepo.deleteById(jobId))

  test("withLease keeps extending the job's lease while the work runs"):
    // Committed, not rolled back: the heartbeat renews the lease from its own connection.
    val shortLeaseManager = JobManager(jobRepo, clock, app.dataSource, leaseDuration = 3.seconds)
    val jobId = transact(transactor):
      val jobId = shortLeaseManager.submitJob(Reconciliation, UUID.randomUUID())
      shortLeaseManager.claimJob(jobId)
      jobId
    try
      val leaseAtClaim = transact(transactor)(jobRepo.findById(jobId).get.lockedUntil.get)
      val leaseAfterWork = shortLeaseManager.withLease(jobId):
        Thread.sleep(2500)
        transact(transactor)(jobRepo.findById(jobId).get.lockedUntil.get)
      assert(leaseAfterWork.isAfter(leaseAtClaim), s"lease $leaseAtClaim was never extended")
    finally transact(transactor)(jobRepo.deleteById(jobId))

  test("submitJob creates a new job once the previous one for the same referenceId has completed"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val firstJobId  = jobManager.submitJob(Reconciliation, referenceID)
        jobManager.claimJob(firstJobId)
        jobManager.completeJob(
          firstJobId,
          ReconciliationInfo(
            accountId                   = referenceID.toString,
            accountName                 = "test",
            reconciliationsCreatedCount = 1,
            processingTimeSeconds       = 1
          )
        )

        val secondJobId = jobManager.submitJob(Reconciliation, referenceID)

        assert(
          secondJobId != firstJobId,
          "a fresh submission after Complete should not be deduped against the finished job"
        )
        assertEquals(jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).size, 2)

  test("submitJob creates a new job once the previous one for the same referenceId has errored"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val firstJobId  = jobManager.submitJob(Reconciliation, referenceID)
        jobManager.claimJob(firstJobId)
        jobManager.markJobInError(firstJobId, new IllegalStateError("boom"))

        val secondJobId = jobManager.submitJob(Reconciliation, referenceID)

        assert(secondJobId != firstJobId, "a fresh submission after Error should not be deduped against the failed job")
        assertEquals(jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).size, 2)

  test("submitAndProcessJob succeeds"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val errorMsg    = "processing failed"
        jobManager.submitAndProcessJob(Reconciliation, referenceID): job =>
          ReconciliationInfo(
            accountId                   = referenceID.toString,
            accountName                 = "test",
            reconciliationsCreatedCount = 1,
            processingTimeSeconds       = 1
          )

        val foundJob = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).head
        assertEquals(foundJob.jobStatus, anseliq.domain.model.JobStatus.Complete)

  test("submitAndProcessJob marks job in error when processFn throws"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val errorMsg    = "processing failed"
        jobManager.submitAndProcessJob(Reconciliation, referenceID): job =>
          throw new RuntimeException(errorMsg)
        val foundJob = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).head
        assertEquals(foundJob.jobStatus, anseliq.domain.model.JobStatus.Error)
        assertEquals(foundJob.errorMessage.get, errorMsg)

  test("complete with the wrong Job Info type"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val jobId       = jobManager.submitJob(JobType.Ingestion, referenceID)
        claimNextUntil(JobType.Ingestion, jobId).get

        intercept[IllegalArgumentError]:
          jobManager.completeJob(
            jobId,
            ReconciliationInfo(
              accountId                   = UUID.randomUUID().toString,
              accountName                 = "test",
              reconciliationsCreatedCount = 1,
              processingTimeSeconds       = 1
            )
          )

  test("pollForJob calls fn with submitted job and marks it InProgress"):
    // pollForJob creates its own transactions so we commit the job without rollBackOnly
    val referenceID = UUID.randomUUID()
    transact(transactor) {
      jobManager.submitJob(Reconciliation, referenceID)
    }

    var processedCount = 0
    val latch          = java.util.concurrent.CountDownLatch(1)

    supervised:
      val fork = forkCancellable:
        jobManager.pollForJob(Reconciliation, 1): job =>
          if job.referenceId == referenceID then
            processedCount += 1
            latch.countDown()

      latch.await()
      fork.cancel()

    assertEquals(processedCount, 1)

    val foundJob = transact(transactor):
      jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).headOption

    assertEquals(foundJob.map(_.jobStatus), Some(InProgress))

    // cleanup committed test data
    transact(transactor):
      foundJob.foreach(j => jobRepo.deleteById(j.id))

  test("submitJob under real concurrency: two racing submitters end up with exactly one active job between them"):
    // Not rolled back — needs two genuinely separate connections/transactions calling submitJob at
    // the same time to exercise the savepoint/unique-violation recovery path inside submitJob. The
    // earlier idempotency tests call submitJob twice sequentially within a single transaction, which
    // never opens the race window at all (the second call's own findActiveJob already sees the
    // first call's uncommitted insert). A CyclicBarrier lines both threads up to start their
    // transact() call as close to simultaneously as possible, maximizing the chance both threads'
    // findActiveJob sees "nothing" before either commits its insert.
    val referenceID = UUID.randomUUID()
    val barrier     = CyclicBarrier(2)
    val results     = ConcurrentLinkedQueue[UUID]()
    val errors      = ConcurrentLinkedQueue[Throwable]()

    def race(): Unit =
      try
        barrier.await()
        val id = transact(transactor)(jobManager.submitJob(Reconciliation, referenceID))
        results.add(id)
      catch case ex: Throwable => errors.add(ex)

    val t1 = Thread(() => race())
    val t2 = Thread(() => race())
    t1.start()
    t2.start()
    t1.join()
    t2.join()

    try
      assert(
        errors.isEmpty,
        s"submitJob must recover from the race internally, not surface it as an error: ${errors.asScala.toList}"
      )
      assertEquals(results.size, 2)
      assertEquals(results.asScala.toSet.size, 1, "both racing submitters must agree on a single job id")

      val allJobs = transact(transactor):
        jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID)
      assertEquals(allJobs.size, 1, "exactly one job row must exist despite the race")
    finally
      transact(transactor):
        jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).foreach(j => jobRepo.deleteById(j.id))

  // Runs a Reconciliation worker loop on its own JobManager (stopClaiming can't be undone) that
  // hands referenceID's job to `onJob`, and completes any other queued Reconciliation job at once.
  private def workerFor(referenceID: ID)(onJob: => Unit)(using Ox): (JobManager, CancellableFork[Unit]) =
    val manager = JobManager(jobRepo, clock, app.dataSource)
    val worker = forkCancellable:
      manager.runWorker(Reconciliation, 1): job =>
        if job.referenceId == referenceID then onJob
        ReconciliationInfo(
          accountId                   = UUID.randomUUID().toString,
          accountName                 = "test",
          reconciliationsCreatedCount = 0,
          processingTimeSeconds       = 0
        )
    (manager, worker)

  private def findJob(referenceID: ID): Job =
    transact(transactor)(jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).head)

  test("stopClaiming lets the running job finish, then the worker loop returns"):
    // Committed, not rolled back: the worker runs the job in its own transactions.
    val referenceID = UUID.randomUUID()
    transact(transactor)(jobManager.submitJob(Reconciliation, referenceID))
    val started = CountDownLatch(1)
    val finish  = CountDownLatch(1)

    supervised:
      val (manager, worker) = workerFor(referenceID):
        started.countDown()
        finish.await()
      started.await()
      manager.stopClaiming()
      finish.countDown()
      assert(timeoutOption(10.seconds)(worker.join()).isDefined, "worker loop didn't return")

    val job = findJob(referenceID)
    assertEquals(job.jobStatus, JobStatus.Complete)
    transact(transactor)(jobRepo.deleteById(job.id))

  test("abandonRunningJobs interrupts the running job and requeues it without counting the attempt"):
    val referenceID = UUID.randomUUID()
    transact(transactor)(jobManager.submitJob(Reconciliation, referenceID))
    val started = CountDownLatch(1)

    supervised:
      val (manager, worker) = workerFor(referenceID):
        started.countDown()
        Thread.sleep(60_000) // runs until interrupted
      started.await()
      manager.stopClaiming()
      manager.abandonRunningJobs(() => worker.cancel())

    val job = findJob(referenceID)
    assertEquals(job.jobStatus, Submitted)
    assertEquals(job.attempts, 0)
    assertEquals(job.lockedUntil, None)
    transact(transactor)(jobRepo.deleteById(job.id))
