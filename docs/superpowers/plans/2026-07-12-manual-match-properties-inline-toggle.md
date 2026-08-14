# Manual Match Properties Inline Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dedicated "Properties" column on `ManualMatchPage`'s movement tables with a `+`/`−` toggle embedded in the Reference ID cell that expands the properties Key | Value table directly below the reference ID text.

**Architecture:** Rework the existing `propertiesCell` helper (added in the previous plan) into a `referenceIdCell` helper that renders the toggle button, the reference ID text, and (when expanded) the nested key/value table, all in one cell. Remove the separate "Properties" column and header from both tables. `expandedProperties: Set[String]` state and the `toggle` helper are unchanged.

**Tech Stack:** Scala.js/Laminar frontend.

## Global Constraints

- No backend changes.

---

### Task 1: Move the properties toggle into the Reference ID cell

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala:78-113` (both table bodies), `:145-170` (the helper to rename/rework)

**Interfaces:**
- Consumes: `MovementPayload.referenceId: String`, `MovementPayload.properties: Map[String, String]` (unchanged); `State.expandedProperties: Set[String]` and `toggle(selected: Set[String], id: String): Set[String]` (both already added by the prior plan, unchanged).
- Produces: n/a (leaf UI change).

- [ ] **Step 1: Replace `propertiesCell` with `referenceIdCell`**

Replace:

```scala
  private def propertiesCell(m: MovementPayload, state: Var[State]): HtmlElement =
    div(
      button(
        cls := "text-xs text-md-primary hover:underline",
        typ := "button",
        onClick.mapToUnit --> Observer[Unit](_ =>
          state.update(st => st.copy(expandedProperties = toggle(st.expandedProperties, m.id)))
        ),
        child.text <-- state.signal.map(s => if s.expandedProperties.contains(m.id) then "▼ Properties" else "▶ Properties")
      ),
      child <-- state.signal.map { s =>
        if s.expandedProperties.contains(m.id) then
          table(
            cls := "mt-1 text-xs border border-gray-200",
            tbody(
              m.properties.toSeq.sortBy(_._1).map { case (k, v) =>
                tr(
                  td(cls := "px-2 py-1 font-medium border-b border-gray-100", k),
                  td(cls := "px-2 py-1 border-b border-gray-100", v)
                )
              }*
            )
          )
        else div()
      }
    )
```

with:

```scala
  private def referenceIdCell(m: MovementPayload, state: Var[State]): HtmlElement =
    div(
      div(
        cls := "flex items-center gap-1",
        button(
          cls := "text-xs w-4 text-md-primary hover:underline",
          typ := "button",
          onClick.mapToUnit --> Observer[Unit](_ =>
            state.update(st => st.copy(expandedProperties = toggle(st.expandedProperties, m.id)))
          ),
          child.text <-- state.signal.map(s => if s.expandedProperties.contains(m.id) then "−" else "+")
        ),
        span(m.referenceId)
      ),
      child <-- state.signal.map { s =>
        if s.expandedProperties.contains(m.id) then
          table(
            cls := "mt-1 text-xs border border-gray-200",
            tbody(
              m.properties.toSeq.sortBy(_._1).map { case (k, v) =>
                tr(
                  td(cls := "px-2 py-1 font-medium border-b border-gray-100", k),
                  td(cls := "px-2 py-1 border-b border-gray-100", v)
                )
              }*
            )
          )
        else div()
      }
    )
```

- [ ] **Step 2: Update the In Movements table**

Replace:

```scala
                Seq("", "Reference ID", "Amount", "Properties"),
                s.inMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedIn.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedIn = toggle(st.selectedIn, m.id))))
                    ),
                    span(m.referenceId),
                    span(m.amount.toString),
                    propertiesCell(m, state)
                  )
                )
```

with:

```scala
                Seq("", "Reference ID", "Amount"),
                s.inMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedIn.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedIn = toggle(st.selectedIn, m.id))))
                    ),
                    referenceIdCell(m, state),
                    span(m.amount.toString)
                  )
                )
```

- [ ] **Step 3: Update the Out Movements table**

Replace:

```scala
                Seq("", "Reference ID", "Amount", "Properties"),
                s.outMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedOut.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedOut = toggle(st.selectedOut, m.id))))
                    ),
                    span(m.referenceId),
                    span(m.amount.toString),
                    propertiesCell(m, state)
                  )
                )
```

with:

```scala
                Seq("", "Reference ID", "Amount"),
                s.outMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedOut.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedOut = toggle(st.selectedOut, m.id))))
                    ),
                    referenceIdCell(m, state),
                    span(m.amount.toString)
                  )
                )
```

- [ ] **Step 4: Compile the frontend**

Run: `sbt frontend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ManualMatchPage.scala
git commit -m "Move properties toggle into Reference ID cell"
```

---

### Task 2: Manual verification

**Files:** none

- [ ] **Step 1:** With the app running, open Manual Match for an account with unreconciled movements. Confirm both tables have exactly three columns (checkbox, Reference ID, Amount) — no separate Properties column.
- [ ] **Step 2:** Confirm each Reference ID cell shows a `+` button before the reference ID text; clicking it flips to `−` and reveals the Key | Value properties table directly below the reference ID text, within the same cell; clicking again collapses it.
- [ ] **Step 3:** Confirm checkbox selection and "Match Selected" still work unaffected.
- [ ] **Step 4:** No commit needed unless a bug is found.
