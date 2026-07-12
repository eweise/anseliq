# Manual Match Movement Properties Column — Design Spec

**Date:** 2026-07-12

## Overview

`ManualMatchPage`'s In/Out Movement tables currently show only Reference ID and Amount
(plus the selection checkbox). Add a "Properties" column showing each movement's raw
ingested key/value data (`MovementPayload.properties: Map[String, String]`), collapsed
by default, expandable per row into a vertical Key | Value table.

## Data

`MovementPayload.properties` (`common/src/main/scala/perfectpair/common/model/Payload.scala:167`,
type `Properties = Map[String, String]`) already carries the raw ingested fields (e.g.
date, description) for each movement — populated on the backend from
`Movement.properties.props` (`backend/src/main/scala/perfectpair/adapter/driving/Payload.scala:148`).
No backend change needed.

## Frontend Change

### `ManualMatchPage.scala` (modified)

**State:** add `expandedProperties: Set[String] = Set.empty` (holds movement ids currently
expanded) to `ManualMatchPage.State`. A single set is shared by both the In and Out
tables since movement ids are globally unique.

**Both tables** (In Movements and Out Movements): add a "Properties" header and column.
Each cell renders:
- A small toggle button showing `"▶ Properties"` when collapsed / `"▼ Properties"` when
  `expandedProperties.contains(m.id)`, using the existing `toggle(selected: Set[String], id: String)`
  helper already defined in this file to flip membership.
- When expanded, a nested two-column table directly below the toggle, one row per
  `m.properties` entry, sorted by key: left column = key, right column = value.

Table headers become `Seq("", "Reference ID", "Amount", "Properties")` for both tables
(unchanged: empty header for the checkbox column, "Reference ID", "Amount").

## Out of Scope

- Sorting/filtering properties.
- Editing property values.
- Showing properties on `UnreconciledMovementsPage` (separate page, not requested).
