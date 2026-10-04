package anseliq.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import ox.*
import anseliq.adapter.driven.{DbDataSource, JobRepoLive}
import anseliq.common.model.ReconciliationInfo
import anseliq.domain.model.JobStatus.InProgress
import anseliq.domain.model.JobType.Reconciliation
import anseliq.domain.model.{IllegalArgumentError, IllegalStateError, JobType}
import anseliq.testing

import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, CyclicBarrier}
import scala.jdk.CollectionConverters.*

class JobManagerSuite extends munit.FunSuite:
  private val dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  private val transactor: Transactor = Transactor(dataSource)
  private val clock      = ClockServiceLive()
  private val jobRepo    = JobRepoLive()
  private val jobManager = JobManager(using jobRepo, clock, dataSource)

  test("submit and complete job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val jobId       = jobManager.submitJob(Reconciliation, referenceID)
        val foundJob    = jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID)
        assert(foundJob.nonEmpty)

        val foundNextJob = jobManager.getNextAvailableJob(Reconciliation).get
        jobManager.completeJob(
          jobId,
          ReconciliationInfo(
            accountId                   = UUID.randomUUID().toString,
            accountName                 = "test",
            reconciliationsCreatedCount = 1,
            processingTimeSeconds       = 1
          )
        )
        assert(jobManager.getNextAvailableJob(Reconciliation).isEmpty)

  test("submit and create error job"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID  = UUID.randomUUID()
        val jobId        = jobManager.submitJob(Reconciliation, referenceID)
        val foundNextJob = jobManager.getNextAvailableJob(Reconciliation).get
        jobManager.markJobInError(jobId, new IllegalStateError("boom"))
        assert(jobManager.getNextAvailableJob(Reconciliation).isEmpty)
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
    "submitJob is idempotent: a second submission while a job is InProgress returns the existing job, not a duplicate"
  ):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val firstJobId  = jobManager.submitJob(Reconciliation, referenceID)
        jobManager.getNextAvailableJob(Reconciliation) // moves it to InProgress

        val secondJobId = jobManager.submitJob(Reconciliation, referenceID)

        assertEquals(secondJobId, firstJobId)
        assertEquals(jobRepo.findByJobTypeAndReferenceId(Reconciliation, referenceID).size, 1)

  test("submitJob creates a new job once the previous one for the same referenceId has completed"):
    transact(transactor):
      testing.rollBackOnly:
        val referenceID = UUID.randomUUID()
        val firstJobId  = jobManager.submitJob(Reconciliation, referenceID)
        jobManager.getNextAvailableJob(Reconciliation)
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
        jobManager.getNextAvailableJob(Reconciliation)
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
        val referenceID  = UUID.randomUUID()
        val jobId        = jobManager.submitJob(JobType.Ingestion, referenceID)
        val foundNextJob = jobManager.getNextAvailableJob(JobType.Ingestion).get

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
