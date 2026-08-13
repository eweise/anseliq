# Exceptions & Disputes — Backend Design

**Date:** 2026-08-13

## Goal

Add a backend feature for flagging unreconciled `Movement`s or in-progress `Reconciliation`s that need
investigation before they can be matched or finalized normally — the "Exceptions & Disputes" concept from
the earlier UI mockup, which currently has only a `ComingSoonPage` placeholder in the nav
(`/components/coming-soon/exceptions`). This spec covers the backend only: schema, domain model, service,
events, and API routes. Wiring a real frontend page in place of the placeholder is a separate follow-up.

## Background

The reconciliation domain today has two paths to an unmatched state: a `Movement` with
`reconciliationId = None` (never matched), or a `Reconciliation` with `status = InProgress` and a nonzero
`balance` (matched but not balanced). Neither has any way to record *why* it's stuck, who's looking at it,
or how it got resolved — that's the gap this feature fills.

The mockup's five example categories span both cases: `MissingCounterpart` and `Duplicate` only make sense
for a `Movement` (there's no `Reconciliation` yet), while `AmountMismatch` only makes sense for a
`Reconciliation` (a single movement doesn't have a "mismatch" on its own). A model that could only attach to
one or the other would force a bad fit for the other three categories, so a `Dispute` targets either.

## Naming

The domain type is **`Dispute`**, not `Exception` — `Exception` collides with `scala.Exception`, which this
codebase already catches directly (`catch case ex: Exception =>` in `Routes.scala`). "Exception" stays the
UI-facing term (matches the nav label); `Dispute` is the internal/backend name throughout.

## Domain Model

`backend/src/main/scala/perfectpair/domain/model/Dispute.scala`, following the `@Table`/`derives DbCodec`
pattern already used by `Reconciliation`:

```scala
package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeTarget derives DbCodec:
  case Movement, Reconciliation

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeCategory derives DbCodec:
  case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum DisputeStatus derives DbCodec:
  case New, Investigating, Escalated, Resolved

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Dispute(
  @Id id:            ID                     = createID(),
  referenceId:       ID,
  targetType:        DisputeTarget,
  category:          DisputeCategory,
  detail:            Option[String]         = None,
  var status:         DisputeStatus          = DisputeStatus.New,
  var resolutionNote: Option[String]         = None,
  var createdAt:      Option[OffsetDateTime] = None,
  var updatedAt:      Option[OffsetDateTime] = None
) derives DbCodec

object Dispute:
  val Table = TableInfo[Dispute, Dispute, UUID]
```

`referenceId` + `targetType` is a polymorphic reference: one column instead of two nullable foreign keys
(`movementId`/`reconciliationId`) plus a mutual-exclusivity `CHECK` constraint. The trade-off is real and
deliberate: Postgres cannot express a native `FOREIGN KEY` on `reference_id` when the target table depends on
a runtime enum value, so referential integrity (does `reference_id` actually point at a real row?) is
enforced only in the service layer, not the database. This is an acceptable trade here because neither
`Movement` nor `Reconciliation` has a delete endpoint today — both are only ever created or status-updated —
so the orphaned-reference risk this trade-off introduces is low in practice.

## Schema

Add to `backend/db/schema.sql`:

```sql
create table recon.dispute
(
    id              uuid primary key,
    reference_id    uuid not null,
    target_type     text not null,
    category        text not null,
    detail          text,
    status          text not null default 'new',
    resolution_note text,
    created_at      timestamptz default CURRENT_TIMESTAMP not null,
    updated_at      timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX dispute_reference_id ON recon.dispute (reference_id);
CREATE INDEX dispute_status_created_at ON recon.dispute (status, created_at);
```

Enum values are stored the same way `ReconciliationStatus`/`Relationship` already are: Magnum's `DbCodec`
derivation for a `@Table`-annotated enum applies `CamelToSnakeCase` to case names (confirmed by the existing
`Relationship.OneToOne` → `'one_to_one'` round-trip), so `DisputeStatus.New` persists as `'new'`,
`DisputeCategory.AmountMismatch` as `'amount_mismatch'`, `DisputeTarget.Movement` as `'movement'`, etc. No
manual string mapping needed.

## Repository

`domain/port/driven/DisputeRepo.scala` needs no custom finder methods — listing (`findAll`) and single-row
lookup (`findById`) are both already provided by `DomainRepo`:

```scala
trait DisputeRepo extends DomainRepo[Dispute, Dispute]
```

`adapter/driven/DisputeRepoLive.scala`, matching `ReconciliationRepoLive`'s shape:

```scala
class DisputeRepoLive extends Repo[Dispute, Dispute, UUID] with DisputeRepo
```

## Service Layer

`backend/src/main/scala/perfectpair/domain/service/DisputeService.scala`, following `ManualReconciler`'s
shape:

```scala
class DisputeService(using
  disputeRepo:  DisputeRepo,
  movementRepo: MovementRepo,
  reconRepo:    ReconciliationRepo,
  eventStore:   EventStore,
  clock:        ClockService
):
  def raise(referenceId: ID, targetType: DisputeTarget, category: DisputeCategory, detail: Option[String]): Txn[Dispute] =
    targetType match
      case DisputeTarget.Movement       => movementRepo.findById(referenceId).ErrIfNotFound("movement", referenceId)
      case DisputeTarget.Reconciliation => reconRepo.findById(referenceId).ErrIfNotFound("reconciliation", referenceId)
    val now = clock.now
    val dispute = disputeRepo.insertReturning(
      Dispute(referenceId = referenceId, targetType = targetType, category = category, detail = detail,
        createdAt = Some(now), updatedAt = Some(now))
    )
    eventStore.publishEvent(DisputeRaised(disputeID = dispute.id))
    dispute

  def updateStatus(disputeId: ID, newStatus: DisputeStatus, resolutionNote: Option[String]): Txn[Dispute] =
    val dispute = disputeRepo.findById(disputeId).ErrIfNotFound("dispute", disputeId)
    if newStatus == DisputeStatus.Resolved && resolutionNote.forall(_.isBlank) then
      throw IllegalArgumentError("resolutionNote", "required when resolving a dispute")
    dispute.status = newStatus
    resolutionNote.foreach(n => dispute.resolutionNote = Some(n))
    dispute.updatedAt = Some(clock.now)
    disputeRepo.update(dispute)
    eventStore.publishEvent(DisputeStatusChanged(disputeID = dispute.id, newStatus = newStatus.toString))
    dispute
```

Two deliberate simplifications:

- **No status-transition guard** beyond requiring a non-blank `resolutionNote` to reach `Resolved`. Any
  other status can move to any other status freely, including reopening a `Resolved` dispute back to
  `Investigating`. This is lighter than `ReconciliationStatus`'s guards (e.g. `unmatch` refuses an
  already-`Voided` reconciliation) — intentionally, since there's no evidence yet of which transitions
  actually need blocking.
- **Raising or updating a `Dispute` never mutates the `Movement`/`Reconciliation` it targets.** It's a purely
  additive side table — no new `hasOpenDispute`-style flag anywhere on the existing domain models.

## Events

Add to `backend/src/main/scala/perfectpair/domain/model/Event.scala`, matching the existing
`ReconciliationVoided`/`ReconciliationWrittenOff` shape:

```scala
case class DisputeRaised(id: ID = UUID.randomUUID(), disputeID: ID) extends Event
case class DisputeStatusChanged(id: ID = UUID.randomUUID(), disputeID: ID, newStatus: String) extends Event
```

## Wiring

`app/Main.scala` instantiates every repo/service as a `given` and passes them into `App`. Add two lines
alongside the existing ones (e.g. near `given reconRepo`/`given manualReconciler`):

```scala
given disputeRepo:    DisputeRepo    = DisputeRepoLive()
given disputeService: DisputeService = DisputeService()
```

`App`'s constructor `using` clause (`adapter/driving/Routes.scala`) needs `disputeRepo` and
`disputeService` added alongside its existing dependencies (`reconRepo`, `manualReconciler`, etc.) so the
new route handlers can use them.

## API Routes

Add to `backend/src/main/scala/perfectpair/adapter/driving/Routes.scala`, following the existing
`HttpCmd`/`HttpPath` conventions:

```scala
@cask.route(HttpPath.disputes, methods = HttpCmd.POST)                  // raise
@cask.route(HttpPath.disputes, methods = HttpCmd.GET)                    // list — all disputes, unfiltered
@cask.route(s"${HttpPath.dispute}/:id/status", methods = HttpCmd.PUT)    // transition status / resolve
```

Add `dispute`/`disputes` path constants to `common/http/HttpPath.scala`, matching the existing
`reconciliation`/`reconSpec` singular/plural pattern.

**List has no server-side status filter for v1** — it returns every `Dispute`, unfiltered, and the frontend
filters client-side. This matches how `ReconciliationsPage` and `UnreconciledMovementsPage` already fetch
their full unfiltered sets rather than passing query params; a dispute queue is operational in size (not
millions of rows like the raw `movement` table), so this is fine until proven otherwise.

Each returned row composes display context the same way `getFundFlowMainView` already does in
`Routes.scala` — sequential repo calls in the route handler, not a raw SQL join:

- `targetType = Movement` → look up the `Movement`, then its account, for `accountName` +
  `referenceLabel = movement.referenceId`
- `targetType = Reconciliation` → look up the `Reconciliation` → its `ReconciliationSpec` → its account, for
  `accountName` + `referenceLabel = reconciliation.description`

## Shared DTOs

Add to `common/model` (used by both frontend and backend, following the existing `*Payload` naming):

```scala
enum DisputeTargetPayload derives ReadWriter: case Movement, Reconciliation
enum DisputeCategoryPayload derives ReadWriter: case AmountMismatch, MissingCounterpart, Duplicate, TimingDifference, DataQuality
enum DisputeStatusPayload derives ReadWriter: case New, Investigating, Escalated, Resolved

case class RaiseDisputeRequest(
  referenceId: String,
  targetType:  DisputeTargetPayload,
  category:    DisputeCategoryPayload,
  detail:      Option[String] = None
) derives ReadWriter

case class UpdateDisputeStatusRequest(
  status:         DisputeStatusPayload,
  resolutionNote: Option[String] = None
) derives ReadWriter

case class DisputePayload(
  id:             String,
  referenceId:    String,
  targetType:     DisputeTargetPayload,
  category:       DisputeCategoryPayload,
  detail:         Option[String],
  status:         DisputeStatusPayload,
  resolutionNote: Option[String],
  accountId:      String,
  accountName:    String,
  referenceLabel: String,
  createdAt:      OffsetDateTime,
  updatedAt:      OffsetDateTime
) derives ReadWriter
```

## Out of Scope

- Automatic dispute detection (e.g. auto-raising a dispute when a movement or reconciliation exceeds its
  `ReconciliationSpec.agingLimit`). Manual-only for v1; this is a natural v2 once the manual flow exists and
  the categories prove useful.
- Assignee / ownership. There's no real user system yet — auth is a single hardcoded login, and the existing
  `User` domain model is unused scaffolding. Adding assignee here would mean building on a foundation that
  doesn't really exist; revisit once real auth/users land.
- Status-transition guards beyond the resolution-note requirement.
- Any mutation of the `Movement`/`Reconciliation` a `Dispute` targets.
- Frontend work of any kind — no `ExceptionsPage`, no "Flag as Exception" affordance on
  `UnreconciledMovementsPage`/`ReconciliationsPage`, no change to `ComingSoonPage`'s routing. This spec is
  backend-only; the frontend follow-up is a separate spec/plan.
- Server-side filtering on the list endpoint.
