# Events Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/v1/events` endpoint that returns typed event payloads, optionally filtered by `?eventType=`.

**Architecture:** Add a sealed `EventPayload` hierarchy to the common package (one case class per `Event` subtype, carrying both event-specific fields and `EventState` metadata). Extend `EventRepo` with `findByEventType`, implement in `EventRepoLive` with a SQL WHERE clause, add a converter in the backend `Payload` object, and wire up a cask route.

**Tech Stack:** Scala 3, cask (HTTP), Magnum (SQL/PostgreSQL), upickle (JSON), munit (tests)

---

## File Map

| File | Action |
|------|--------|
| `common/src/main/scala/perfectpair/common/model/Payload.scala` | Add `EventPayload` sealed trait + 3 subtypes |
| `common/src/main/scala/perfectpair/common/http/http.scala` | Add `val events` to `HttpPath` |
| `backend/src/main/scala/perfectpair/domain/port/driven/EventRepo.scala` | Add `findByEventType` |
| `backend/src/main/scala/perfectpair/adapter/driven/EventRepoLive.scala` | Implement `findByEventType` |
| `backend/src/test/scala/perfectpair/adapter/driven/EventStateRepoLiveSuite.scala` | Add `findByEventType` tests |
| `backend/src/main/scala/perfectpair/adapter/driving/Payload.scala` | Add `eventPayloadFromDomain` |
| `backend/src/test/scala/perfectpair/adapter/driving/PayloadSuite.scala` | New — unit tests for `eventPayloadFromDomain` |
| `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala` | Add `eventRepo` using param + `getEvents` route |

---

### Task 1: Add EventPayload types and path constant to common

**Files:**
- Modify: `common/src/main/scala/perfectpair/common/model/Payload.scala`
- Modify: `common/src/main/scala/perfectpair/common/http/http.scala`

- [ ] **Step 1: Add `EventPayload` sealed hierarchy to `Payload.scala`**

Append to the end of `common/src/main/scala/perfectpair/common/model/Payload.scala` (after the last `def combine...` function):

```scala
sealed trait EventPayload derives ReadWriter

case class MoneyMovementsIngestedPayload(
  id:             String,
  eventType:      String,
  movementSpecID: String,
  status:         String,
  retryCount:     Int,
  errorMessage:   Option[String],
  createdAt:      Option[OffsetDateTime],
  updatedAt:      Option[OffsetDateTime]
) extends EventPayload

case class AccountReconciledPayload(
  id:           String,
  eventType:    String,
  accountID:    String,
  status:       String,
  retryCount:   Int,
  errorMessage: Option[String],
  createdAt:    Option[OffsetDateTime],
  updatedAt:    Option[OffsetDateTime]
) extends EventPayload

case class NewFileDetectedPayload(
  id:              String,
  eventType:       String,
  fileInventoryID: String,
  status:          String,
  retryCount:      Int,
  errorMessage:    Option[String],
  createdAt:       Option[OffsetDateTime],
  updatedAt:       Option[OffsetDateTime]
) extends EventPayload
```

`status` is `String` (not an enum) so we avoid mirroring the backend `EventStatus` enum into common — same pattern as `ReconciliationPayload`. uPickle's sealed trait derivation adds a `$type` discriminator automatically.

- [ ] **Step 2: Add `events` path constant to `HttpPath`**

In `common/src/main/scala/perfectpair/common/http/http.scala`, add inside `object HttpPath`:

```scala
val events = s"$prefix/events"
```

Place it alongside the other plural resource paths (e.g. after `val jobs`).

- [ ] **Step 3: Verify common compiles**

```bash
sbt "commonJVM/compile"
```

Expected: `[success] Total time: ...`

- [ ] **Step 4: Commit**

```bash
git add common/src/main/scala/perfectpair/common/model/Payload.scala \
        common/src/main/scala/perfectpair/common/http/http.scala
git commit -m "feat(common): add EventPayload sealed hierarchy and events path constant"
```

---

### Task 2: Add `findByEventType` to EventRepo and EventRepoLive (TDD)

**Files:**
- Modify: `backend/src/test/scala/perfectpair/adapter/driven/EventStateRepoLiveSuite.scala`
- Modify: `backend/src/main/scala/perfectpair/domain/port/driven/EventRepo.scala`
- Modify: `backend/src/main/scala/perfectpair/adapter/driven/EventRepoLive.scala`

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/scala/perfectpair/adapter/driven/EventStateRepoLiveSuite.scala`:

```scala
test("findByEventType returns only events matching the given type"):
  val repo = EventRepoLive()
  transact(transactor):
    rollBackOnly:
      val event1 = EventState(eventType = "MoneyMovementsIngested", payload = "{}")
      val event2 = EventState(eventType = "AccountReconciled", payload = "{}")
      repo.insert(event1)
      repo.insert(event2)
      val found = repo.findByEventType("MoneyMovementsIngested")
      assertEquals(found.size, 1)
      assertEquals(found.head.eventType, "MoneyMovementsIngested")

test("findByEventType returns empty vector when no events match"):
  val repo = EventRepoLive()
  transact(transactor):
    rollBackOnly:
      val found = repo.findByEventType("NonExistentType")
      assert(found.isEmpty)
```

- [ ] **Step 2: Run tests to see them fail**

```bash
sbt "backend/testOnly perfectpair.adapter.driven.EventStateRepoLiveSuite"
```

Expected: compile error — `value findByEventType is not a member of EventRepoLive`

- [ ] **Step 3: Add `findByEventType` to the `EventRepo` port**

In `backend/src/main/scala/perfectpair/domain/port/driven/EventRepo.scala`:

```scala
package perfectpair.domain.port.driven

import perfectpair.domain.model.EventState

trait EventRepo extends DomainRepo[EventState, EventState]:
  def getNextPendingEvent(eventType: String, maxRetries: Int): Txn[Option[EventState]]
  def findByEventType(eventType: String): Txn[Vector[EventState]]
```

- [ ] **Step 4: Implement `findByEventType` in `EventRepoLive`**

In `backend/src/main/scala/perfectpair/adapter/driven/EventRepoLive.scala`, add the override:

```scala
package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.domain.model.{EventState, EventStatus}
import perfectpair.domain.port.driven.{EventRepo, Txn}

import java.util.UUID

class EventRepoLive extends Repo[EventState, EventState, UUID] with EventRepo:
  val e = EventState.Table

  override def getNextPendingEvent(eventType: String, maxRetries: Int): Txn[Option[EventState]] =
    val found = sql"""
      SELECT ${e.all}
      FROM $e
      WHERE status in(${EventStatus.Pending}, ${EventStatus.Error})
      AND event_type = $eventType
      AND retry_count < $maxRetries
      ORDER BY created_at
      LIMIT 1
      FOR UPDATE SKIP LOCKED
    """.query[EventState].run()
    found.headOption

  override def findByEventType(eventType: String): Txn[Vector[EventState]] =
    sql"SELECT ${e.all} FROM $e WHERE event_type = $eventType".query[EventState].run()
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
sbt "backend/testOnly perfectpair.adapter.driven.EventStateRepoLiveSuite"
```

Expected: `[info] Tests: succeeded 7, failed 0, ...` (5 existing + 2 new)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/port/driven/EventRepo.scala \
        backend/src/main/scala/perfectpair/adapter/driven/EventRepoLive.scala \
        backend/src/test/scala/perfectpair/adapter/driven/EventStateRepoLiveSuite.scala
git commit -m "feat(backend): add EventRepo.findByEventType with SQL implementation"
```

---

### Task 3: Add `eventPayloadFromDomain` to backend Payload (TDD)

**Files:**
- Create: `backend/src/test/scala/perfectpair/adapter/driving/PayloadSuite.scala`
- Modify: `backend/src/main/scala/perfectpair/adapter/driving/Payload.scala`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/scala/perfectpair/adapter/driving/PayloadSuite.scala`:

```scala
package perfectpair.adapter.driving

import perfectpair.common.model.*
import perfectpair.domain.model.*

import java.util.UUID

class PayloadSuite extends munit.FunSuite:

  test("eventPayloadFromDomain converts MoneyMovementsIngested"):
    val specId  = UUID.randomUUID()
    val event   = MoneyMovementsIngested(movementSpecID = specId)
    val state   = EventState.newEventState(event)
    val result  = Payload.eventPayloadFromDomain(state)
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
    val result    = Payload.eventPayloadFromDomain(state)
    assert(result.isInstanceOf[AccountReconciledPayload])
    val payload = result.asInstanceOf[AccountReconciledPayload]
    assertEquals(payload.id, event.id.toString)
    assertEquals(payload.eventType, "AccountReconciled")
    assertEquals(payload.accountID, accountId.toString)
    assertEquals(payload.status, "Pending")

  test("eventPayloadFromDomain converts NewFileDetected"):
    val fileInventoryId = UUID.randomUUID()
    val event           = NewFileDetected(fileInventoryID = fileInventoryId)
    val state           = EventState.newEventState(event)
    val result          = Payload.eventPayloadFromDomain(state)
    assert(result.isInstanceOf[NewFileDetectedPayload])
    val payload = result.asInstanceOf[NewFileDetectedPayload]
    assertEquals(payload.id, event.id.toString)
    assertEquals(payload.eventType, "NewFileDetected")
    assertEquals(payload.fileInventoryID, fileInventoryId.toString)
    assertEquals(payload.status, "Pending")
```

Note: this is a pure unit test — no database, no `transact`, no `rollBackOnly`.

- [ ] **Step 2: Run test to see it fail**

```bash
sbt "backend/testOnly perfectpair.adapter.driving.PayloadSuite"
```

Expected: compile error — `value eventPayloadFromDomain is not a member of object Payload`

- [ ] **Step 3: Add `eventPayloadFromDomain` to backend `Payload.scala`**

Append to `backend/src/main/scala/perfectpair/adapter/driving/Payload.scala` (inside the `object Payload`):

```scala
  def eventPayloadFromDomain(state: EventState): EventPayload =
    upickle.read[Event](state.payload) match
      case e: MoneyMovementsIngested =>
        MoneyMovementsIngestedPayload(
          id             = state.id.toString,
          eventType      = state.eventType,
          movementSpecID = e.movementSpecID.toString,
          status         = state.status.toString,
          retryCount     = state.retryCount,
          errorMessage   = state.errorMessage,
          createdAt      = state.createdAt,
          updatedAt      = state.updatedAt
        )
      case e: AccountReconciled =>
        AccountReconciledPayload(
          id           = state.id.toString,
          eventType    = state.eventType,
          accountID    = e.accountID.toString,
          status       = state.status.toString,
          retryCount   = state.retryCount,
          errorMessage = state.errorMessage,
          createdAt    = state.createdAt,
          updatedAt    = state.updatedAt
        )
      case e: NewFileDetected =>
        NewFileDetectedPayload(
          id              = state.id.toString,
          eventType       = state.eventType,
          fileInventoryID = e.fileInventoryID.toString,
          status          = state.status.toString,
          retryCount      = state.retryCount,
          errorMessage    = state.errorMessage,
          createdAt       = state.createdAt,
          updatedAt       = state.updatedAt
        )
```

`upickle.read[Event]` matches the pattern in `EventStore.scala` (line 33). The `ReadWriter[Event]` is derived in `Event.scala` and available via `import perfectpair.domain.model.*`.

- [ ] **Step 4: Run tests to verify they pass**

```bash
sbt "backend/testOnly perfectpair.adapter.driving.PayloadSuite"
```

Expected: `[info] Tests: succeeded 3, failed 0, ...`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/perfectpair/adapter/driving/Payload.scala \
        backend/src/test/scala/perfectpair/adapter/driving/PayloadSuite.scala
git commit -m "feat(backend): add Payload.eventPayloadFromDomain converter"
```

---

### Task 4: Add `getEvents` route to App

**Files:**
- Modify: `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala`

- [ ] **Step 1: Add `eventRepo` to `App`'s `using` parameters**

In `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala`, update the `App` class signature to include `eventRepo: EventRepo`:

```scala
class App(using
  dataSource:         DataSource,
  accountRepo:        AccountRepo,
  reconRepo:          ReconciliationRepo,
  fundFlowConfigRepo: FundFlowConfigRepo,
  movementSpecRepo:   MovementSpecRepo,
  movementRepo:       MovementRepo,
  fundFlowOperations: FundFlowOperations,
  jobManager:         JobManager,
  ingestor:           Ingestor,
  reconciler:         InMemoryReconciler,
  jobRepo:            JobRepo,
  reconSpecRepo:      ReconciliationSpecRepo,
  eventRepo:          EventRepo
) extends cask.MainRoutes:
```

`eventRepo` is already wired as a `given EventRepo = EventRepoLive()` in `Main.scala` (line 35), so `new App().main(args)` will pick it up automatically — no change to `Main.scala` needed.

- [ ] **Step 2: Add the `getEvents` route**

Add the following method to the `App` class, alongside the other GET routes (e.g. after the `jobsByType` route):

```scala
  @cask.route(s"${HttpPath.events}", methods = HttpCmd.GET)
  def getEvents(request: cask.Request): String =
    val eventTypeFilter = request.queryParams.get("eventType").flatMap(_.headOption)
    transact(transactor):
      val states = eventTypeFilter match
        case Some(et) => eventRepo.findByEventType(et)
        case None     => eventRepo.findAll
      write(states.map(Payload.eventPayloadFromDomain))
```

`request.queryParams` returns `Map[String, Seq[String]]`. With no `eventType` param, `findAll` is called (inherited from `DomainRepo` via `Repo`). The `write` call uses `ReadWriter[EventPayload]` derived in common; it's in scope via `import upickle.default.*` and `import perfectpair.common.model.*` already present in `Routes.scala`.

- [ ] **Step 3: Verify backend compiles**

```bash
sbt "backend/compile"
```

Expected: `[success] Total time: ...`

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/scala/perfectpair/adapter/driving/Routes.scala
git commit -m "feat(backend): add GET /api/v1/events endpoint with optional eventType filter"
```
