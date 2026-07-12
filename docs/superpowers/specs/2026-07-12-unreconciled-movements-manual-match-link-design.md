# Link Unreconciled Movements Page to Manual Match — Design Spec

**Date:** 2026-07-12

## Overview

`UnreconciledMovementsPage(accountId)` (see `docs/superpowers/specs/2026-07-12-unreconciled-movements-by-account-design.md`)
currently has no way to get to the existing manual-match flow for that account. Add a
"Manual Match" link next to its "Back" button, matching the button `ReconciliationsPage`
already uses for the same purpose.

## Change

### `UnreconciledMovementsPage.scala` (modified)

Add, immediately after the existing `Buttons.linkButton("Back", ...)` call:

```scala
Buttons.textButton("Manual Match", Observer[Unit](_ => Nav.toManualMatch(accountId)))
```

`accountId` is already in scope as the page's parameter; `Nav.toManualMatch(accountId: String): Unit`
and `ManualMatchPage(accountId: String)` already exist and are unchanged.

## Out of Scope

- Any change to `ManualMatchPage` itself.
- Any change to how `ReconciliationsPage` links to Manual Match.
