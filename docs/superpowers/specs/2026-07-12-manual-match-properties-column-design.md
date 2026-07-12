# Manual Match Movement Properties Toggle — Design Spec

**Date:** 2026-07-12 (revised)

## Overview

`ManualMatchPage`'s In/Out Movement tables currently show only Reference ID and Amount
(plus the selection checkbox). Add an inline `+`/`−` toggle in the Reference ID cell
that expands each movement's raw ingested key/value data
(`MovementPayload.properties: Map[String, String]`) as a vertical Key | Value table
directly below the reference ID text, within that same cell.

**Revision note:** an earlier version of this spec put the toggle and expanded table in
their own dedicated "Properties" column. That approach has been replaced by embedding the
toggle and expanded table directly in the Reference ID cell instead — no separate column.

## Data

`MovementPayload.properties` (`common/src/main/scala/perfectpair/common/model/Payload.scala:167`,
type `Properties = Map[String, String]`) already carries the raw ingested fields (e.g.
date, description) for each movement — populated on the backend from
`Movement.properties.props` (`backend/src/main/scala/perfectpair/adapter/driving/Payload.scala:148`).
No backend change needed.

## Frontend Change

### `ManualMatchPage.scala` (modified)

**State:** `expandedProperties: Set[String] = Set.empty` on `ManualMatchPage.State` (holds
movement ids currently expanded). A single set is shared by both the In and Out tables
since movement ids are globally unique.

**Both tables** (In Movements and Out Movements): table headers stay
`Seq("", "Reference ID", "Amount")` — no new column. The Reference ID cell for each
movement becomes:
- A `+` (collapsed) / `−` (expanded, i.e. `expandedProperties.contains(m.id)`) toggle
  button immediately before the reference ID text, using the `toggle(selected: Set[String], id: String)`
  helper (object-level in this file) to flip membership.
- When expanded, a nested two-column table directly below the reference ID text (still
  inside the same cell), one row per `m.properties` entry, sorted by key: left column =
  key, right column = value.

## Out of Scope

- Sorting/filtering properties.
- Editing property values.
- Showing properties on `UnreconciledMovementsPage` (separate page, not requested).
