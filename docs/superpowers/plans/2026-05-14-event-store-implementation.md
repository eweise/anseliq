# EventStore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `publishEvent` and `receiveEvent` on `EventStore`, backed by a new `recon.event` PostgreSQL table with retry-on-failure semantics.

**Architecture:** Full hexagonal split — `Event` model + `EventStatus` enum + `RetryableException` in the domain, `EventRepo` port trait, `EventRepoLive` Magnum adapter, and `EventStore` service holding all retry/status logic. Core per-event logic lives in a `processNextEvent` helper (testable within a transaction); `receiveEvent` wraps it in a polling loop.

**Tech Stack:** Scala 3, Magnum (SQL), PostgreSQL, munit, ox (structured concurrency / scheduling)

---

## File Map

| Action | Path | Purpose |
|---|---|---|
| Modify | `backend/db/schema.sql` | Add `recon.event` table, index, trigger |
| Create | `backend/src/main/scala/perfectpair/domain/model/Event.scala` | `Event`, `EventStatus`, `RetryableException` |
| Create | `backend/src/main/scala/perfectpair/domain/port/driven/EventRepo.scala` | `EventRepo` trait |
| Create | `backend/src/main/scala/perfectpair/adapter/driven/EventRepoLive.scala` | Magnum implementation |
| Modify | `backend/src/main/scala/perfectpair/domain/service/EventStore.scala` | `publishEvent`, `processNextEvent`, `receiveEvent` |
| Create | `backend/src/test/scala/perfectpair/adapter/driven/EventRepoLiveSuite.scala` | DB-level repo tests |
| Create | `backend/src/test/scala/perfectpair/domain/service/EventStoreSuite.scala` | Service-level tests |

---

### Task 1: Add event table to schema

**Files:**
- Modify: `backend/db/schema.sql`

- [ ] **Step 1: Add table, index, and trigger to schema.sql**

Append the following before the closing of the file (after the last `CREATE TRIGGER` block):

```sql
create table recon.event
(
    id            uuid primary key,
    event_type    text                                  not null,
    payload       text                                  not null,
    status        text                                  not null default 'Pending',
    retry_count   int                                   not null default 0,
    error_message text,
    created_at    timestamptz default CURRENT_TIMESTAMP not null,
    updated_at    timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX event_status_created_at ON recon.event (status, created_at);

CREATE TRIGGER update_event
    BEFORE INSERT OR UPDATE
    ON recon.event
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();
```

- [ ] **Step 2: Recreate the database**

```bash
make create-db
```

Expected: no errors; psql exits with code 0.

- [ ] **Step 3: Verify the table exists**

```bash
/Library/PostgreSQL/17/bin/psql -p 5433 -Upostgres -d recon_db -c "\d recon.event"
```

Expected: table with columns id, event_type, payload, status, retry_count, error_message, created_at, updated_at.

- [ ] **Step 4: Commit**

```bash
git add backend/db/schema.sql
git commit -m "feat: add recon.event table to schema"
```

---

### Task 2: Create Event domain model

**Files:**
- Create: `backend/src/main/scala/perfectpair/domain/model/Event.scala`

- [ ] **Step 1: Create the file**

```scala
package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum EventStatus derives DbCodec:
  case Pending, InProgress, Consumed, Error

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Event(
  @Id id:        ID                     = createID(),
  eventType:     String,
  payload:       String,
  status:        EventStatus            = EventStatus.Pending,
  retryCount:    Int                    = 0,
  errorMessage:  Option[String]         = None,
  var createdAt: Option[OffsetDateTime] = None,
  var updatedAt: Option[OffsetDateTime] = None
) derives DbCodec

object Event:
  val Table = TableInfo[Event, Event, UUID]

class RetryableException(message: String) extends Exception(message)
```

- [ ] **Step 2: Verify compilation**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/compile"
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/model/Event.scala
git commit -m "feat: add Event model, EventStatus enum, RetryableException"
```

---

### Task 3: Create EventRepo port

**Files:**
- Create: `backend/src/main/scala/perfectpair/domain/port/driven/EventRepo.scala`

- [ ] **Step 1: Create the file**

```scala
package perfectpair.domain.port.driven

import perfectpair.domain.model.Event

trait EventRepo extends DomainRepo[Event, Event]:
  def getNextPendingEvent(maxRetries: Int): Txn[Option[Event]]
```

- [ ] **Step 2: Verify compilation**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/compile"
```

Expected: `[success]`

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/port/driven/EventRepo.scala
git commit -m "feat: add EventRepo port trait"
```

---

### Task 4: TDD — EventRepoLive

**Files:**
- Create: `backend/src/test/scala/perfectpair/adapter/driven/EventRepoLiveSuite.scala`
- Create: `backend/src/main/scala/perfectpair/adapter/driven/EventRepoLive.scala`

- [ ] **Step 1: Write the failing tests**

```scala
package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.model.{Event, EventStatus}
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class EventRepoLiveSuite extends munit.FunSuite:
  var transactor: Transactor = null
  var dataSource: DataSource = null

  override def beforeAll(): Unit =
    dataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
    transactor = Transactor(dataSource)

  test("insert and findById"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "TestEvent", payload = """{"key":"value"}""")
        repo.insert(event)
        val found = repo.findById(event.id)
        assert(found.isDefined)
        assertEquals(found.get.eventType, "TestEvent")
        assertEquals(found.get.status, EventStatus.Pending)
        assertEquals(found.get.retryCount, 0)

  test("getNextPendingEvent returns oldest pending event"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event1 = Event(eventType = "TestEvent", payload = "{}")
        val event2 = Event(eventType = "TestEvent", payload = "{}")
        repo.insert(event1)
        repo.insert(event2)
        val found = repo.getNextPendingEvent(maxRetries = 3)
        assert(found.isDefined)

  test("getNextPendingEvent returns None when no pending events exist"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val found = repo.getNextPendingEvent(maxRetries = 3)
        assert(found.isEmpty)

  test("getNextPendingEvent excludes InProgress events"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "TestEvent", payload = "{}", status = EventStatus.InProgress)
        repo.insert(event)
        val found = repo.getNextPendingEvent(maxRetries = 3)
        assert(found.isEmpty)

  test("getNextPendingEvent excludes events that have reached maxRetries"):
    val repo = EventRepoLive()
    transact(transactor):
      rollBackOnly:
        val maxRetries = 3
        val event      = Event(eventType = "TestEvent", payload = "{}", retryCount = maxRetries)
        repo.insert(event)
        val found = repo.getNextPendingEvent(maxRetries = maxRetries)
        assert(found.isEmpty)
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/testOnly perfectpair.adapter.driven.EventRepoLiveSuite"
```

Expected: compilation error — `EventRepoLive` not found.

- [ ] **Step 3: Create EventRepoLive**

```scala
package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.domain.model.{Event, EventStatus}
import perfectpair.domain.port.driven.{EventRepo, Txn}

import java.util.UUID

class EventRepoLive extends Repo[Event, Event, UUID] with EventRepo:
  val e = Event.Table

  override def getNextPendingEvent(maxRetries: Int): Txn[Option[Event]] =
    sql"""
      SELECT ${e.all}
      FROM $e
      WHERE status = ${EventStatus.Pending}
      AND retry_count < $maxRetries
      ORDER BY created_at
      LIMIT 1
      FOR UPDATE SKIP LOCKED
    """.query[Event].run().headOption
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/testOnly perfectpair.adapter.driven.EventRepoLiveSuite"
```

Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/scala/perfectpair/adapter/driven/EventRepoLiveSuite.scala \
        backend/src/main/scala/perfectpair/adapter/driven/EventRepoLive.scala
git commit -m "feat: implement EventRepoLive with getNextPendingEvent"
```

---

### Task 5: TDD — EventStore.publishEvent

**Files:**
- Create: `backend/src/test/scala/perfectpair/domain/service/EventStoreSuite.scala`
- Modify: `backend/src/main/scala/perfectpair/domain/service/EventStore.scala`

- [ ] **Step 1: Write the failing test**

```scala
package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.{DbDataSource, EventRepoLive}
import perfectpair.domain.model.{Event, EventStatus, RetryableException}
import perfectpair.testing.rollBackOnly

import javax.sql.DataSource

class EventStoreSuite extends munit.FunSuite:
  private val dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  private val transactor: Transactor = Transactor(dataSource)
  private val clock                  = ClockService()
  private val eventRepo              = EventRepoLive()
  private val eventStore             = EventStore(using eventRepo, clock, dataSource)

  test("publishEvent inserts event with Pending status"):
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "OrderPlaced", payload = """{"orderId":"123"}""")
        eventStore.publishEvent(event)
        val found = eventRepo.findById(event.id)
        assert(found.isDefined)
        assertEquals(found.get.status, EventStatus.Pending)
        assertEquals(found.get.retryCount, 0)
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/testOnly perfectpair.domain.service.EventStoreSuite"
```

Expected: compilation error — `EventStore` constructor/method not found.

- [ ] **Step 3: Implement EventStore with publishEvent**

Replace the contents of `backend/src/main/scala/perfectpair/domain/service/EventStore.scala`:

```scala
package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import ox.scheduling.{Schedule, repeat}
import perfectpair.domain.model.{Event, EventStatus, RetryableException}
import perfectpair.domain.port.driven.{EventRepo, Txn}

import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

class EventStore(using eventRepo: EventRepo, clock: ClockService, dataSource: DataSource):

  def publishEvent(event: Event): Txn[Unit] =
    eventRepo.insert(event)

  def processNextEvent(maxRetries: Int)(consumer: Event => Unit): Txn[Unit] =
    eventRepo.getNextPendingEvent(maxRetries).foreach { event =>
      val inProgress = event.copy(status = EventStatus.InProgress, updatedAt = Some(clock.now))
      eventRepo.update(inProgress)
      try
        consumer(inProgress)
        eventRepo.update(inProgress.copy(status = EventStatus.Consumed, updatedAt = Some(clock.now)))
      catch
        case ex: RetryableException =>
          if inProgress.retryCount + 1 >= maxRetries then
            eventRepo.update(inProgress.copy(
              status       = EventStatus.Error,
              errorMessage = Some(ex.getMessage),
              updatedAt    = Some(clock.now)
            ))
          else
            eventRepo.update(inProgress.copy(
              status     = EventStatus.Pending,
              retryCount = inProgress.retryCount + 1,
              updatedAt  = Some(clock.now)
            ))
        case ex: Exception =>
          eventRepo.update(inProgress.copy(
            status       = EventStatus.Error,
            errorMessage = Some(ex.getMessage),
            updatedAt    = Some(clock.now)
          ))

  def receiveEvent(pollingSeconds: Int, maxRetries: Int)(consumer: Event => Unit): Unit =
    repeat(Schedule.fixedInterval(pollingSeconds.seconds)) {
      given transactor: Transactor = Transactor(dataSource)
      transact(transactor) {
        processNextEvent(maxRetries)(consumer)
      }
    }
```

- [ ] **Step 4: Run test to confirm it passes**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/testOnly perfectpair.domain.service.EventStoreSuite"
```

Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/scala/perfectpair/domain/service/EventStore.scala \
        backend/src/test/scala/perfectpair/domain/service/EventStoreSuite.scala
git commit -m "feat: implement EventStore.publishEvent"
```

---

### Task 6: TDD — EventStore.processNextEvent success path

**Files:**
- Modify: `backend/src/test/scala/perfectpair/domain/service/EventStoreSuite.scala`

- [ ] **Step 1: Add the test**

Add to `EventStoreSuite` (inside the class, after the existing test):

```scala
  test("processNextEvent calls consumer and marks event Consumed"):
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "OrderPlaced", payload = """{"orderId":"456"}""")
        eventStore.publishEvent(event)

        var consumed = false
        eventStore.processNextEvent(maxRetries = 3) { _ => consumed = true }

        assertEquals(consumed, true)
        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Consumed)

  test("processNextEvent does nothing when no pending events"):
    transact(transactor):
      rollBackOnly:
        var called = false
        eventStore.processNextEvent(maxRetries = 3) { _ => called = true }
        assertEquals(called, false)
```

- [ ] **Step 2: Run tests**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/testOnly perfectpair.domain.service.EventStoreSuite"
```

Expected: all 3 tests pass (implementation already complete from Task 5).

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/scala/perfectpair/domain/service/EventStoreSuite.scala
git commit -m "test: add processNextEvent success path tests"
```

---

### Task 7: TDD — EventStore.processNextEvent retry and error paths

**Files:**
- Modify: `backend/src/test/scala/perfectpair/domain/service/EventStoreSuite.scala`

- [ ] **Step 1: Add retry and error tests**

Add to `EventStoreSuite`:

```scala
  test("processNextEvent increments retryCount and keeps Pending on RetryableException"):
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "OrderPlaced", payload = "{}")
        eventStore.publishEvent(event)

        eventStore.processNextEvent(maxRetries = 3) { _ =>
          throw RetryableException("temporary failure")
        }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Pending)
        assertEquals(found.retryCount, 1)

  test("processNextEvent marks Error when retryCount reaches maxRetries on RetryableException"):
    transact(transactor):
      rollBackOnly:
        val maxRetries = 3
        val event      = Event(eventType = "OrderPlaced", payload = "{}", retryCount = maxRetries - 1)
        eventRepo.insert(event)

        eventStore.processNextEvent(maxRetries = maxRetries) { _ =>
          throw RetryableException("still failing")
        }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Error)
        assertEquals(found.errorMessage, Some("still failing"))

  test("processNextEvent marks Error on non-retryable exception"):
    transact(transactor):
      rollBackOnly:
        val event = Event(eventType = "OrderPlaced", payload = "{}")
        eventStore.publishEvent(event)

        eventStore.processNextEvent(maxRetries = 3) { _ =>
          throw RuntimeException("fatal failure")
        }

        val found = eventRepo.findById(event.id).get
        assertEquals(found.status, EventStatus.Error)
        assertEquals(found.errorMessage, Some("fatal failure"))
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/testOnly perfectpair.domain.service.EventStoreSuite"
```

Expected: the 3 new tests fail (implementation not yet handling exceptions).

Wait — the implementation in Task 5 already includes exception handling. These tests should pass without additional changes. If they do fail, check that the `try/catch` in `processNextEvent` is syntactically correct for Scala 3 (use `try ... catch case ...` form).

- [ ] **Step 3: Run tests and confirm they pass**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/testOnly perfectpair.domain.service.EventStoreSuite"
```

Expected: all 6 tests pass.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/scala/perfectpair/domain/service/EventStoreSuite.scala
git commit -m "test: add retry and error path tests for processNextEvent"
```

---

### Task 8: Full test run

- [ ] **Step 1: Run all backend tests**

```bash
cd /Users/eweise/projects/perfectpair && sbt "backend/test"
```

Expected: all tests pass, no regressions.

- [ ] **Step 2: If any test fails, investigate and fix before committing**

Check test output for the failing test name, locate the file, and fix the issue.

- [ ] **Step 3: Final commit if any fixes were needed**

```bash
git add -p
git commit -m "fix: resolve test failures after EventStore implementation"
```
