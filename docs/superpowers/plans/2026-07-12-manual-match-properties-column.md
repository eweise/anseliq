# Manual Match Properties Column Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an expandable "Properties" column to both movement tables on `ManualMatchPage`, showing each movement's raw ingested key/value data in a vertical Key | Value table when toggled open.

**Architecture:** No backend change — `MovementPayload.properties: Map[String, String]` already carries this data. Add `expandedProperties: Set[String]` to `ManualMatchPage.State`, promote the existing local `toggle` helper to object scope so a new `propertiesCell` helper can share it, and use `propertiesCell` in both the In and Out movement table row builders.

**Tech Stack:** Scala.js/Laminar frontend.

## Global Constraints

- No backend changes.
- No changes to `UnreconciledMovementsPage` (out of scope per spec).

---

### Task 1: Add expandable Properties column to `ManualMatchPage`

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/ManualMatchPage.scala` (full file — `State`, the `toggle` helper's location, table headers/rows, and a new `propertiesCell` helper)

**Interfaces:**
- Consumes: `MovementPayload.properties: Map[String, String]` (pre-existing, `common/src/main/scala/perfectpair/common/model/Payload.scala:167`); `Table.create(headers: Seq[String], rows: Seq[Seq[HtmlElement]]): HtmlElement` (pre-existing).
- Produces: n/a (leaf UI change, nothing else in the codebase calls into `ManualMatchPage` internals).

- [ ] **Step 1: Add `expandedProperties` to `State`**

In `frontend/src/main/scala/perfectpair/view/pages/ManualMatchPage.scala`, replace:

```scala
  case class State(
    inMovements:  Seq[MovementPayload] = Seq.empty,
    outMovements: Seq[MovementPayload] = Seq.empty,
    selectedIn:   Set[String]          = Set.empty,
    selectedOut:  Set[String]          = Set.empty,
    description:  String               = "Manual match",
    message:      Option[String]       = None
  )
```

with:

```scala
  case class State(
    inMovements:        Seq[MovementPayload] = Seq.empty,
    outMovements:       Seq[MovementPayload] = Seq.empty,
    selectedIn:         Set[String]          = Set.empty,
    selectedOut:        Set[String]          = Set.empty,
    expandedProperties: Set[String]          = Set.empty,
    description:        String               = "Manual match",
    message:            Option[String]       = None
  )
```

- [ ] **Step 2: Promote `toggle` to object scope and add `propertiesCell`**

Still in the same file, remove the local `def toggle(...)` currently inside `apply`:

```scala
    def toggle(selected: Set[String], id: String): Set[String] =
      if selected.contains(id) then selected - id else selected + id
```

(delete that whole local def from inside `apply`), and add these as private members of the `ManualMatchPage` object, after the `apply` method (i.e. at the same indentation level as `apply`, not nested inside it):

```scala
  private def toggle(selected: Set[String], id: String): Set[String] =
    if selected.contains(id) then selected - id else selected + id

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

Since `toggle` moved out of `apply`, its two existing call sites (`selectedIn`/`selectedOut` toggling) keep working unchanged — they're calling a method now defined on the object instead of a local closure, same signature.

- [ ] **Step 3: Add the column to both tables**

Replace the In Movements table body:

```scala
            child <-- state.signal.map { s =>
              Table.create(
                Seq("", "Reference ID", "Amount"),
                s.inMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedIn.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedIn = toggle(st.selectedIn, m.id))))
                    ),
                    span(m.referenceId),
                    span(m.amount.toString)
                  )
                )
              )
            }
```

with:

```scala
            child <-- state.signal.map { s =>
              Table.create(
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
              )
            }
```

And replace the Out Movements table body:

```scala
            child <-- state.signal.map { s =>
              Table.create(
                Seq("", "Reference ID", "Amount"),
                s.outMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedOut.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedOut = toggle(st.selectedOut, m.id))))
                    ),
                    span(m.referenceId),
                    span(m.amount.toString)
                  )
                )
              )
            }
```

with:

```scala
            child <-- state.signal.map { s =>
              Table.create(
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
              )
            }
```

- [ ] **Step 4: Compile the frontend**

Run: `sbt frontend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/ManualMatchPage.scala
git commit -m "Add expandable Properties column to Manual Match movement tables"
```

---

### Task 2: Manual verification

**Files:** none

- [ ] **Step 1:** With the app running, open Manual Match for an account with unreconciled movements. Confirm both tables show a "Properties" column with a collapsed "▶ Properties" toggle per row.
- [ ] **Step 2:** Click a toggle — confirm it flips to "▼ Properties" and reveals a Key | Value table beneath it listing that movement's properties; click again to collapse.
- [ ] **Step 3:** Confirm selecting/deselecting checkboxes and submitting a match still works as before (unaffected by this change).
- [ ] **Step 4:** No commit needed unless a bug is found.
