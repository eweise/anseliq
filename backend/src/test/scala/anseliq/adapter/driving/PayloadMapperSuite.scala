package anseliq.adapter.driving

import anseliq.common.model.*
import anseliq.domain.model.*

import java.util.UUID

class PayloadMapperSuite extends munit.FunSuite:

  test("eventPayloadFromDomain converts MoneyMovementsIngested"):
    val specId = UUID.randomUUID()
    val event = MoneyMovementsIngested(
      movementSpecID       = specId,
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
    assertEquals(payload.movementSpecID, specId.toString)
    assertEquals(payload.status, "Pending")
    assertEquals(payload.retryCount, 0)
    assert(payload.createdAt.isDefined)

  test("eventPayloadFromDomain converts AccountReconciled"):
    val accountId = UUID.randomUUID()
    val event     = AccountReconciled(accountID = accountId)
    val state     = EventState.newEventState(event)
    val result    = PayloadMapper.eventPayloadFromDomain(state)
    assert(result.isInstanceOf[AccountReconciledPayload])
    val payload = result.asInstanceOf[AccountReconciledPayload]
    assertEquals(payload.id, event.id.toString)
    assertEquals(payload.eventType, "AccountReconciled")
    assertEquals(payload.accountID, accountId.toString)
    assertEquals(payload.status, "Pending")

  test("eventPayloadFromDomain converts NewFileDetected"):
    val fileInventoryId = UUID.randomUUID()
    val event = NewFileDetected(
      fileInventoryID = fileInventoryId,
      filePath        = "a",
      source          = "b"
    )
    val state  = EventState.newEventState(event)
    val result = PayloadMapper.eventPayloadFromDomain(state)
    assert(result.isInstanceOf[NewFileDetectedPayload])
    val payload = result.asInstanceOf[NewFileDetectedPayload]
    assertEquals(payload.id, event.id.toString)
    assertEquals(payload.eventType, "NewFileDetected")
    assertEquals(payload.fileInventoryID, fileInventoryId.toString)
    assertEquals(payload.status, "Pending")
