# Link Unreconciled Movements Page to Manual Match Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Manual Match" link on `UnreconciledMovementsPage` that takes the user to the existing manual-match flow for the account being viewed.

**Architecture:** Reuse the existing `Nav.toManualMatch(accountId: String)` navigation function and `ManualMatchPage(accountId: String)` route — both already exist and are unchanged. Add one `Buttons.textButton` call to `UnreconciledMovementsPage`, following the same pattern `ReconciliationsPage` already uses.

**Tech Stack:** Scala.js/Laminar frontend.

## Global Constraints

- No changes to `ManualMatchPage`, `Nav.toManualMatch`, or `ReconciliationsPage`'s own link — out of scope per the spec.

---

### Task 1: Add "Manual Match" button to `UnreconciledMovementsPage`

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/UnreconciledMovementsPage.scala:46`

**Interfaces:**
- Consumes: `Nav.toManualMatch(accountId: String): Unit` (pre-existing, `frontend/src/main/scala/perfectpair/routing/Nav.scala:43-44`); `Buttons.textButton(label: String, obs: Observer[Unit]): HtmlElement` (pre-existing, `frontend/src/main/scala/perfectpair/view/components/Buttons.scala`); `accountId: String` (the page's own parameter, already in scope at line 19).
- Produces: n/a (leaf UI change).

- [ ] **Step 1: Add the button**

In `frontend/src/main/scala/perfectpair/view/pages/UnreconciledMovementsPage.scala`, replace:

```scala
        Buttons.linkButton("Back", Observer[Unit](_ => Nav.back())),
```

with:

```scala
        Buttons.linkButton("Back", Observer[Unit](_ => Nav.back())),
        Buttons.textButton("Manual Match", Observer[Unit](_ => Nav.toManualMatch(accountId))),
```

- [ ] **Step 2: Compile the frontend**

Run: `sbt frontend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/UnreconciledMovementsPage.scala
git commit -m "Link unreconciled movements page to Manual Match"
```

---

### Task 2: Manual verification

**Files:** none

- [ ] **Step 1:** With the app running (see prior plan's Task 4 notes on Postgres requirement), navigate to a recon account's unreconciled-movements page and confirm a "Manual Match" button/link appears next to "Back", and clicking it navigates to that account's Manual Match page (`/components/accounts/:id/manual-match`).
- [ ] **Step 2:** No commit needed unless a bug is found.
