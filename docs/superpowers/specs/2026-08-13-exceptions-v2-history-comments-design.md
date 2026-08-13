# Exceptions v2 — Comment/History Thread + Detail Page

**Date:** 2026-08-13

## Goal

Add a unified comment/status-change thread to `Dispute`, and a detail page reachable by clicking a dispute
in the Exceptions list, showing full target-entity details (the actual `Movement` or `Reconciliation`, not
just the denormalized label the list view uses) plus the thread. Status changes move from the list page's
inline quick-actions onto this detail page, alongside free-form commenting.

## Background

v1 (`docs/superpowers/specs/2026-08-13-disputes-design.md`, `...-exceptions-page-design.md`) gave `Dispute` a
bare status field and a `resolutionNote` required only when resolving, with status changed via inline
buttons directly on the list. There's no record of *when* a status changed, *why* (beyond the one-shot
resolution note), or any way to leave a note without also changing status. This spec replaces that with a
single append-only thread per dispute: each entry optionally carries a body and/or a new status, so "change
status with a note," "change status with no note" (matching today's Investigate/Escalate behavior), and
"just leave a comment" are all the same operation with different fields populated.

## Domain Model

`DisputeComment` (`backend/src/main/scala/perfectpair/domain/model/Dispute.scala`, alongside the existing
`Dispute`/`DisputeTarget`/`DisputeCategory`/`DisputeStatus`):

```scala
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class DisputeComment(
  @Id id:        ID                     = createID(),
  disputeId:     ID,
  body:          Option[String]         = None,
  newStatus:     Option[DisputeStatus]  = None, // Some = this entry also changed status; None = plain comment
  var createdAt: Option[OffsetDateTime] = None
) derives DbCodec

object DisputeComment:
  val Table = TableInfo[DisputeComment, DisputeComment, UUID]
```

**`Dispute.resolutionNote` is removed.** It's now redundant with the thread — the "why" for a resolution is
just the `body` of whichever comment set `newStatus = Resolved`. Nothing in the shipped UI ever displayed
`resolutionNote` (the list page only used it server-side for validation), so removing it has no user-facing
regression to account for.

**Validation** (in the service, replacing the old "resolutionNote required when resolving" rule):
- `newStatus` empty (plain comment) → `body` required — an empty entry with neither text nor a status change
  is meaningless.
- `newStatus = Some(Resolved)` → `body` required — same rule as v1, just relocated from a dedicated field to
  the comment body.
- `newStatus = Some(other)` → `body` optional — matches v1, where Investigate/Escalate/Reopen never required
  a reason.

## Schema

```sql
ALTER TABLE recon.dispute DROP COLUMN resolution_note;

create table recon.dispute_comment
(
    id         uuid primary key,
    dispute_id uuid not null REFERENCES recon.dispute (id) ON DELETE CASCADE,
    body       text,
    new_status text,
    created_at timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX dispute_comment_dispute_id ON recon.dispute_comment (dispute_id, created_at);
```

## Repository

`DisputeCommentRepo` needs one custom finder beyond what `DomainRepo` provides — ordered retrieval per
dispute:

```scala
trait DisputeCommentRepo extends DomainRepo[DisputeComment, DisputeComment]:
  def findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]]
```

```scala
class DisputeCommentRepoLive extends Repo[DisputeComment, DisputeComment, UUID] with DisputeCommentRepo:
  val c = DisputeComment.Table

  def findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]] =
    sql"""
      SELECT ${c.all}
      FROM $c AS a
      WHERE dispute_id = $disputeId
      ORDER BY created_at
    """.query[DisputeComment].run()
```

(Matches `ReconciliationRepoLive`'s exact `val r = Table; sql"SELECT ${r.all} FROM $r AS a WHERE ..."`
pattern.)

(Ascending order — oldest first, newest at the bottom of the thread, matching the usual comment-thread
convention.)

## Service

`DisputeService.updateStatus` is replaced by a single `addComment`, taking over its responsibilities:

```scala
class DisputeService(using
  disputeRepo:  DisputeRepo,
  commentRepo:  DisputeCommentRepo,
  movementRepo: MovementRepo,
  reconRepo:    ReconciliationRepo,
  eventStore:   EventStore,
  clock:        ClockService
):

  def raise(referenceId: ID, targetType: DisputeTarget, category: DisputeCategory, detail: Option[String]): Txn[Dispute] =
    // unchanged from v1

  def addComment(disputeId: ID, body: Option[String], newStatus: Option[DisputeStatus]): Txn[DisputeComment] =
    val dispute = disputeRepo.findById(disputeId).ErrIfNotFound("dispute", disputeId)

    val bodyRequired = newStatus match
      case None                        => true
      case Some(DisputeStatus.Resolved) => true
      case Some(_)                      => false
    if bodyRequired && body.forall(_.isBlank) then
      throw IllegalArgumentError("body", "required for this entry")

    newStatus.foreach { s =>
      dispute.status = s
      dispute.updatedAt = Some(clock.now)
      disputeRepo.update(dispute)
      eventStore.publishEvent(DisputeStatusChanged(disputeID = dispute.id, newStatus = s.toString))
    }

    val comment = commentRepo.insertReturning(
      DisputeComment(disputeId = disputeId, body = body, newStatus = newStatus, createdAt = Some(clock.now))
    )
    eventStore.publishEvent(DisputeCommentAdded(disputeID = disputeId, commentID = comment.id))
    comment
```

`raise` is unchanged — the thread starts empty when a dispute is created; `detail` stays exactly as it is
today (the reason given at raise time), separate from the thread.

**Existing tests migrate, not just add.** `DisputeServiceSuite`'s eight tests call `.updateStatus(...)`
directly; since that method is removed, all eight need to become `.addComment(...)` calls (dropping the
now-unused `resolutionNote` assertions in favor of asserting on the returned `DisputeComment`'s `body`/
`newStatus`, and the `Dispute`'s `status` via a fresh `disputeRepo.findById` — matching how the write-off
tests already re-fetch to assert side effects). This is a rename-and-adapt of every existing test, not a
new addition.

New event, alongside the existing `DisputeRaised`/`DisputeStatusChanged` in `Event.scala`:

```scala
case class DisputeCommentAdded(id: ID = UUID.randomUUID(), disputeID: ID, commentID: ID) extends Event
```

## API Routes

**Removed:** `PUT /api/v1/dispute/:id/status` and the `UpdateDisputeStatusRequest` DTO.

**Added:**

```scala
@cask.route(s"${HttpPath.dispute}/:id", methods = HttpCmd.GET)
```

Composes the full detail view in the route handler (same style as `disputeContext`/`getFundFlowMainView`):
the `Dispute` (as the existing `DisputePayload`), the full target entity (`MovementPayload` if
`targetType = Movement`, `ReconciliationPayload` if `Reconciliation` — reusing the existing conversion
functions, no new entity DTOs needed), and the comment thread.

```scala
@cask.route(s"${HttpPath.dispute}/:id/comments", methods = HttpCmd.POST)
```

Calls `disputeService.addComment`, then returns the freshly recomposed detail payload (same shape as the
GET above) so the frontend can replace its whole page state from one response instead of a second
round-trip.

Both routes need a new `Payload.disputeCommentPayloadFromDomain(c: DisputeComment): DisputeCommentPayload`
conversion function (`adapter/driving/Payload.scala`), reusing the existing `disputeStatusFromDomain` for
the `newStatus` field — same shape as every other `*fromDomain` function already in that file.

## Shared DTOs

`common/model/Payload.scala`:

```scala
case class DisputeCommentPayload(
  id:        String,
  body:      Option[String],
  newStatus: Option[DisputeStatusPayload],
  createdAt: OffsetDateTime
) derives ReadWriter

case class DisputeDetailPayload(
  dispute:        DisputePayload,
  movement:        Option[MovementPayload],
  reconciliation:  Option[ReconciliationPayload],
  comments:        Seq[DisputeCommentPayload]
) derives ReadWriter

case class AddDisputeCommentRequest(
  body:      Option[String]              = None,
  newStatus: Option[DisputeStatusPayload] = None
) derives ReadWriter
```

`UpdateDisputeStatusRequest` is deleted (superseded by `AddDisputeCommentRequest`).

`HttpPath` needs no new constants — `HttpPath.dispute` (singular, already exists) covers both new routes
via `/:id` and `/:id/comments` suffixes.

## Frontend

**`ExceptionDetailPage(id: String)`** (new), routed at `/components/exceptions/:id`:
- Fetches `GET /api/v1/dispute/:id` on mount.
- Shows: status/category pills (reusing `ExceptionsPage`'s existing `statusTone`), the account name, and
  target-specific details — for a `Movement`: reference ID, amount, in/out account IDs, properties; for a
  `Reconciliation`: description, applied rules, balance, status, created/updated timestamps.
- Renders the comment thread: each entry shows its body (if any), a status-change pill (if `newStatus` is
  set), and a relative/formatted timestamp (reusing `ViewHelper.formatDateTime`).
- One form at the bottom: a status `select` (options: "No change" + the four `DisputeStatusPayload` values,
  reusing `Form.selectField`'s pattern) and a text input for the comment body, with a single "Post" button
  that calls `POST /api/v1/dispute/:id/comments` and replaces page state with the response — no separate
  reload call needed, unlike v1's reload-after-mutate pattern.
- "Back" link to `/components/exceptions`, matching `ReconciliationsPage`/`UnreconciledMovementsPage`'s
  existing convention.

**`ExceptionsPage`** simplifies: the inline Investigate/Escalate/Resolve/Reopen buttons and the
`editingResolution`/local-`Var` note-input machinery are removed entirely. Each row becomes a click-through
to its detail page (same `href` + `onClick.preventDefault + Nav.push` pattern `FundFlowsPage`'s rows already
use, so it degrades gracefully — hover shows the real URL, middle-click/right-click still work). The
"Actions" column is dropped since there's nothing left to act on from the list.

**`Nav.scala`**: add `toExceptionDetail(id: String): Unit = push(s"/components/exceptions/$id")`.

**`Routes.scala`**: add `path("exceptions" / segment)(id => ExceptionDetailPage(id))` alongside the existing
`path("exceptions")(ExceptionsPage())` — `frontroute`'s `firstMatch` already handles this kind of bare-path
vs. `/segment` split correctly elsewhere in this file (e.g. `fund-flows` vs. `accounts/:id/...`).

## Out of Scope

- Editing or deleting a comment once posted.
- Automatic system-generated comments (e.g. "auto-raised by aging job") — still manual-only, per the v1
  scope decision.
- Any change to how disputes are raised (`detail` field, raise flow, or the raise endpoint itself).
- Pagination on the comment thread — expected to stay small per dispute.
