# Unreconciled Movements by Account Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user click the "Unreconciled Count" value for a reconciliation account on `FundFlowPage` and land on a page listing that account's unreconciled movements.

**Architecture:** Expose the already-implemented (but currently unrouted) `MovementRepo.findUnReconciledByAccount` via a new GET `/api/v1/account/:id/unreconciled-movements` route. Repurpose the existing but unwired `UnreconciledMovementsPage` from movement-spec-scoped to account-scoped, matching the `accountId: String` route-param pattern already used by `ReconciliationsPage`/`ManualMatchPage`. Wire a new `Nav` route and a clickable cell on `FundFlowPage`.

**Tech Stack:** Scala 3 backend (cask routes, Magnum), Scala.js/Laminar frontend, frontroute for client-side routing.

## Global Constraints

- Backend: follow hexagonal architecture; no new ZIO code (`backend/CLAUDE.md`).
- No dedicated automated test exists in this codebase for cask HTTP routes (confirmed: `git show --stat` on the most recent route-adding commit, `969a3b6`, touched only `Routes.scala`/`Main.scala`, no test files). `MovementRepo.findUnReconciledByAccount` itself is already covered by `backend/src/test/scala/onparity/adapter/driven/MovementRepoLiveSuite.scala:88-101`. Do not add a new backend test file for this route — verify by compiling and, where practical, a manual curl check.
- Frontend has no test infrastructure at all (no test directory under `frontend/src`). Verify frontend changes by compiling (`sbt frontend/compile`) and, in the final task, running the app and clicking through manually.
- Keep the spec-scoped endpoint `movements/:movementSpecID/unreconciled` and its consumer `ManualMatchPage` untouched.

---

### Task 1: Backend — add `GET /api/v1/account/:id/unreconciled-movements` route

**Files:**
- Modify: `backend/src/main/scala/onparity/adapter/driving/Routes.scala:151-157` (insert a new route immediately after the existing `movementsBySpecID` method)

**Interfaces:**
- Consumes: `movementRepo.findUnReconciledByAccount(accountID: ID): Txn[Vector[Movement]]` (already defined in `backend/src/main/scala/onparity/domain/port/driven/MovementRepo.scala:12` and implemented in `backend/src/main/scala/onparity/adapter/driven/MovementRepoLive.scala:38-45`); `Payload.movementfromDomainModel(dmMovement: Movement): MovementPayload` (already defined in `backend/src/main/scala/onparity/adapter/driving/Payload.scala:141-148`).
- Produces: HTTP endpoint `GET ${HttpPath.account}/:id/unreconciled-movements` returning `Seq[MovementPayload]` as JSON — consumed by Task 2's frontend fetch.

- [ ] **Step 1: Add the route**

In `backend/src/main/scala/onparity/adapter/driving/Routes.scala`, immediately after the closing line of `movementsBySpecID` (currently lines 151-157, ending `write(payload)`), insert:

```scala
  @cask.route(s"${HttpPath.account}/:id/unreconciled-movements", methods = HttpCmd.GET)
  def unreconciledMovementsByAccount(id: String): String =
    transact(transactor):
      val movements = movementRepo.findUnReconciledByAccount(UUID.fromString(id))
      val payload   = movements.map(Payload.movementfromDomainModel)
      write(payload)
```

- [ ] **Step 2: Compile the backend**

Run: `sbt backend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 3: (If a local Postgres + backend server is already running) manual verification**

Run: `curl http://localhost:8080/api/v1/account/<some-recon-account-id>/unreconciled-movements`
Expected: a JSON array (possibly empty) of movement objects, not a 404 or 500. If no local server/DB is available, skip this step — Task 4 covers end-to-end verification once the frontend is wired up.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/scala/onparity/adapter/driving/Routes.scala
git commit -m "Add GET account/:id/unreconciled-movements route"
```

---

### Task 2: Frontend — repurpose `UnreconciledMovementsPage` to be account-scoped

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/UnreconciledMovementsPage.scala` (full rewrite of the file body)
- Modify: `frontend/src/main/scala/onparity/routing/Nav.scala` (the `toUnreconciledMovements` def)
- Modify: `frontend/src/main/scala/onparity/routing/Routes.scala` (the `unreconciled-movements` route entry)

**Interfaces:**
- Consumes: `AppState.activeFundFlow: Var[Option[FundFlowPayload]]`, `HttpPath.fundFlows`, `HttpPath.account`, `ApiClient.get[T](url: String, token: ...)`, `Table.create`, `Buttons.linkButton`, `AccountPayload` (has `id: Option[String]`, `name: String`), `MovementPayload` (has `referenceId`, `amount`, `inAccountId`, `outAccountId`) — all pre-existing.
- Produces: `UnreconciledMovementsPage.apply(accountId: String): HtmlElement`; `Nav.toUnreconciledMovements(accountId: String): Unit` — both consumed by Task 3.

- [ ] **Step 1: Rewrite `UnreconciledMovementsPage.scala`**

Replace the entire file content with:

```scala
package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{AccountPayload, MovementPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, Table}
import upickle.default.*

object UnreconciledMovementsPage:

  case class State(
    accounts:  Seq[AccountPayload]  = Seq.empty,
    movements: Seq[MovementPayload] = Seq.empty
  )

  def apply(accountId: String): HtmlElement =
    val ffId  = AppState.activeFundFlow.now().flatMap(_.id).getOrElse("")
    val state = Var(State())

    div(
      ApiClient
        .get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token) --> {
        case Right(accs) => state.update(_.copy(accounts = accs))
        case Left(e)     => AppState.globalError.set(Some(e))
      },
      ApiClient.get[Seq[MovementPayload]](
        HttpPath.url(s"${HttpPath.account}/$accountId/unreconciled-movements"),
        SessionStore.token
      ) --> {
        case Right(ms) => state.update(_.copy(movements = ms))
        case Left(e)   => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center",
        h2(
          cls := "text-2xl font-medium mb-6",
          child.text <-- state.signal.map { s =>
            val name = s.accounts.find(_.id.contains(accountId)).map(_.name).getOrElse(accountId)
            s"$name — Unreconciled Movements"
          }
        ),
        Buttons.linkButton("Back", Observer[Unit](_ => Nav.back())),
        child <-- state.signal.map { s =>
          val nameById = s.accounts.flatMap(a => a.id.map(_ -> a.name)).toMap
          Table.create(
            Seq("Reference ID", "Amount", "In Account", "Out Account"),
            s.movements.map(m =>
              Seq(
                span(m.referenceId),
                span(m.amount.toString),
                span(nameById.getOrElse(m.inAccountId, m.inAccountId)),
                span(nameById.getOrElse(m.outAccountId, m.outAccountId))
              )
            )
          )
        }
      )
    )
```

- [ ] **Step 2: Update `Nav.scala`**

In `frontend/src/main/scala/onparity/routing/Nav.scala`, replace:

```scala
  def toUnreconciledMovements(): Unit = push("/components/unreconciled-movements")
```

with:

```scala
  def toUnreconciledMovements(accountId: String): Unit =
    push(s"/components/accounts/$accountId/unreconciled-movements")
```

- [ ] **Step 3: Update the frontend `Routes.scala`**

In `frontend/src/main/scala/onparity/routing/Routes.scala`, replace:

```scala
          path("unreconciled-movements")(UnreconciledMovementsPage())
```

with (keep it grouped among the other `"accounts" / segment / ...` routes, i.e. move this line up next to `path("accounts" / segment / "manual-match")(...)`):

```scala
          path("accounts" / segment / "unreconciled-movements")(accountId => UnreconciledMovementsPage(accountId))
```

The full `firstMatch(...)` block should read:

```scala
        firstMatch(
          path("fund-flows")(FundFlowsPage()),
          path("fund-flow")(FundFlowPage()),
          path("maintain-fund-flow")(MaintainFundFlowPage()),
          path("accounts" / segment / "maintain")(accountId => MaintainAccountPage(accountId)),
          path("accounts" / segment / "recon-specs")(accountId => ReconSpecsPage(accountId)),
          path("accounts" / segment / "maintain-recon-spec")(accountId => MaintainReconSpecPage(accountId)),
          path("accounts" / segment / "ingest")(accountId => IngestPage(accountId)),
          path("accounts" / segment / "reconciliations")(accountId => ReconciliationsPage(accountId)),
          path("accounts" / segment / "manual-match")(accountId => ManualMatchPage(accountId)),
          path("accounts" / segment / "unreconciled-movements")(accountId => UnreconciledMovementsPage(accountId)),
          path("accounts")(AccountsPage()),
          path("maintain-movement-spec")(MaintainMovementSpecPage()),
          path("jobs")(JobsPage()),
          path("events")(EventsPage())
        )
```

- [ ] **Step 4: Compile the frontend**

Run: `sbt frontend/compile`
Expected: `[success]` with no errors. (This will surface any leftover reference to the old no-arg `UnreconciledMovementsPage()` call or `toUnreconciledMovements()` call — there should be none left after this task, since nothing else in the codebase calls either today.)

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/UnreconciledMovementsPage.scala frontend/src/main/scala/onparity/routing/Nav.scala frontend/src/main/scala/onparity/routing/Routes.scala
git commit -m "Make UnreconciledMovementsPage account-scoped"
```

---

### Task 3: Frontend — link "Unreconciled Count" on `FundFlowPage` to the new page

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala:153-171` (the `Account` tab row-rendering block)

**Interfaces:**
- Consumes: `Nav.toUnreconciledMovements(accountId: String): Unit` (from Task 2).
- Produces: n/a (leaf UI change).

- [ ] **Step 1: Make the Unreconciled Count cell a conditional link**

In `frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala`, the `Account` tab currently renders (lines 153-171):

```scala
                  s.accounts.sortBy(_.name).map { account =>
                    val accountId    = account.id.getOrElse("")
                    val status       = s.reconcileStatuses.getOrElse(accountId, ReconcileStatus.Idle)
                    val unreconCount = s.unreconciledCounts.getOrElse(accountId, 0)
                    Seq(
                      span(cls := "inline-block w-40", account.name),
                      span(if account.isRecon then "Recon" else "Source"),
                      span(cls := "text-right w-full inline-block", f"$$${account.balance}%.2f"),
                      span(
                        cls := "text-right w-full inline-block",
                        if (account.isRecon) then f"$$${account.reconciledBalance}%.2f" else ""
                      ),
                      span(
                        cls := "text-right w-full inline-block",
                        if (account.isRecon) then f"$$${account.unreconciledBalance}%.2f" else ""
                      ),
                      span(cls := "text-right w-full inline-block", unreconCount.toString)
                    )
                  }
```

Replace the final `Seq(...)` element (the `unreconCount` span) so the whole block reads:

```scala
                  s.accounts.sortBy(_.name).map { account =>
                    val accountId    = account.id.getOrElse("")
                    val status       = s.reconcileStatuses.getOrElse(accountId, ReconcileStatus.Idle)
                    val unreconCount = s.unreconciledCounts.getOrElse(accountId, 0)
                    val unreconCell =
                      if account.isRecon then
                        span(
                          cls := "text-right w-full inline-block text-blue-600 hover:underline cursor-pointer",
                          onClick --> (_ => Nav.toUnreconciledMovements(accountId)),
                          unreconCount.toString
                        )
                      else span(cls := "text-right w-full inline-block", unreconCount.toString)
                    Seq(
                      span(cls := "inline-block w-40", account.name),
                      span(if account.isRecon then "Recon" else "Source"),
                      span(cls := "text-right w-full inline-block", f"$$${account.balance}%.2f"),
                      span(
                        cls := "text-right w-full inline-block",
                        if (account.isRecon) then f"$$${account.reconciledBalance}%.2f" else ""
                      ),
                      span(
                        cls := "text-right w-full inline-block",
                        if (account.isRecon) then f"$$${account.unreconciledBalance}%.2f" else ""
                      ),
                      unreconCell
                    )
                  }
```

- [ ] **Step 2: Compile the frontend**

Run: `sbt frontend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/FundFlowPage.scala
git commit -m "Link Unreconciled Count to the unreconciled movements page"
```

---

### Task 4: End-to-end manual verification

**Files:** none (verification only)

**Interfaces:** none

- [ ] **Step 1: Start the app**

Use this project's `run` skill (or `sbt devServer` plus the backend's normal startup) to get the backend and frontend dev server running against a local Postgres with some seeded fund flow / recon account / unreconciled movement data. (`PopulateS3Buckets.scala` under `backend/src/test/scala/onparity/testing/` or the `docs/other/` notes may already describe how test data is seeded in this environment — check there if unsure how to get a recon account with a nonzero unreconciled count.)

- [ ] **Step 2: Click through**

In the browser: open a fund flow with at least one reconciliation account that has unreconciled movements, go to the Accounts tab, and confirm:
- The "Unreconciled Count" cell for a recon account is styled as a link and, on click, navigates to a page titled `"<Account Name> — Unreconciled Movements"` listing the expected movements with correct In/Out account names.
- The "Unreconciled Count" cell for a source account (isRecon = false, count always 0) is plain text, not clickable.
- The page's "Back" button returns to the `FundFlowPage` Accounts tab.

- [ ] **Step 3: No commit needed**

This task is verification only — nothing to commit unless a bug is found and fixed (in which case, repeat the relevant task's steps).
