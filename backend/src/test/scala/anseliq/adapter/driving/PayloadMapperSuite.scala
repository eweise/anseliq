package anseliq.adapter.driving

import anseliq.common.model.*
import anseliq.domain.model.*

import java.time.OffsetDateTime
import java.util.UUID

class PayloadMapperSuite extends munit.FunSuite:

  test("eventPayloadFromDomain converts MoneyMovementsIngested"):
    val specId = UUID.randomUUID()
    val event = MoneyMovementsIngested(
      movementSpecId       = specId,
      movementSpecName     = "test",
      fileName             = "test",
      recordCount          = 0,
      ingestionTimeSeconds = 0,
      lastProcessedLine    = None
    )
    val state  = EventState.newEventState(event)
    val result = PayloadMapper.eventPayloadFromDomain(state)
    assert(result.isInstanceOf[MoneyMovementsIngestedPayload])
    val payload = result.asInstanceOf[MoneyMovementsIngestedPayload]
    assertEquals(payload.id, event.id.toString)
    assertEquals(payload.eventType, "MoneyMovementsIngested")
    assertEquals(payload.movementSpecId, specId.toString)
    assert(payload.createdAt.isDefined)

  test("eventPayloadFromDomain converts AccountReconciled"):
    val accountId = UUID.randomUUID()
    val event     = AccountReconciled(accountId = accountId)
    val state     = EventState.newEventState(event)
    val result    = PayloadMapper.eventPayloadFromDomain(state)
    assert(result.isInstanceOf[AccountReconciledPayload])
    val payload = result.asInstanceOf[AccountReconciledPayload]
    assertEquals(payload.id, event.id.toString)
    assertEquals(payload.eventType, "AccountReconciled")
    assertEquals(payload.accountId, accountId.toString)

  test("eventPayloadFromDomain converts NewFileDetected"):
    val fileInventoryId = UUID.randomUUID()
    val event = NewFileDetected(
      fileInventoryId = fileInventoryId,
      filePath        = "a",
      source          = "b"
    )
    val state  = EventState.newEventState(event)
    val result = PayloadMapper.eventPayloadFromDomain(state)
    assert(result.isInstanceOf[NewFileDetectedPayload])
    val payload = result.asInstanceOf[NewFileDetectedPayload]
    assertEquals(payload.id, event.id.toString)
    assertEquals(payload.eventType, "NewFileDetected")
    assertEquals(payload.fileInventoryId, fileInventoryId.toString)

  test("jobPayloadFromDomain copies every field"):
    val createdAt = OffsetDateTime.parse("2026-10-19T10:00:00Z")
    val updatedAt = createdAt.plusMinutes(2)
    val runAt     = createdAt.plusMinutes(3)
    val job = Job(
      referenceId  = UUID.randomUUID(),
      jobType      = JobType.CreateFileInventory,
      jobStatus    = JobStatus.Submitted,
      jobInfoJson  = Some(upickle.default.write[JobInfo](FileInventoryInfo(filesQueued = 3))),
      errorMessage = Some("Connection refused"),
      attempts     = 2,
      runAt        = Some(runAt),
      createdAt    = Some(createdAt),
      updatedAt    = Some(updatedAt)
    )
    val payload = PayloadMapper.jobPayloadFromDomain(job)
    assertEquals(payload.id, Some(job.id.toString))
    assertEquals(payload.referenceId, job.referenceId.toString)
    assertEquals(payload.jobType, JobTypeInfo.CreateFileInventory)
    assertEquals(payload.jobStatus, JobInfoStatus.Submitted)
    assertEquals(payload.jobInfoJson, job.jobInfoJson)
    assertEquals(payload.errorMessage, Some("Connection refused"))
    assertEquals(payload.attempts, 2)
    assertEquals(payload.runAt, Some(runAt))
    assertEquals(payload.createdAt, Some(createdAt))
    assertEquals(payload.updatedAt, Some(updatedAt))

  test("jobPayloadFromDomain leaves optional fields empty for a fresh job"):
    val job     = Job(referenceId = UUID.randomUUID(), jobType = JobType.Ingestion)
    val payload = PayloadMapper.jobPayloadFromDomain(job)
    assertEquals(payload.jobStatus, JobInfoStatus.Submitted)
    assertEquals(payload.jobInfoJson, None)
    assertEquals(payload.errorMessage, None)
    assertEquals(payload.attempts, 0)
    assertEquals(payload.runAt, None)

  test("jobPayloadFromDomain maps every job status"):
    JobStatus.values.foreach: status =>
      val job = Job(referenceId = UUID.randomUUID(), jobType = JobType.Ingestion, jobStatus = status)
      assertEquals(PayloadMapper.jobPayloadFromDomain(job).jobStatus.toString, status.toString)

  // GenerateReconStats has no JobTypeInfo counterpart (the jobs route rejects it), so it's left out.
  test("jobPayloadFromDomain maps every job type exposed to the UI"):
    JobTypeInfo.values.foreach: jobTypeInfo =>
      val job = Job(referenceId = UUID.randomUUID(), jobType = JobType.valueOf(jobTypeInfo.toString))
      assertEquals(PayloadMapper.jobPayloadFromDomain(job).jobType, jobTypeInfo)

  test("jobPayloadFromDomain payload round-trips through JSON"):
    val job = Job(
      referenceId  = UUID.randomUUID(),
      jobType      = JobType.Reconciliation,
      jobStatus    = JobStatus.Submitted,
      errorMessage = Some("boom"),
      attempts     = 1,
      runAt        = Some(OffsetDateTime.parse("2026-10-19T10:05:00Z"))
    )
    val payload = PayloadMapper.jobPayloadFromDomain(job)
    assertEquals(upickle.default.read[JobInfoPayload](upickle.default.write(payload)), payload)
