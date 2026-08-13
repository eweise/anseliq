# Manual Match Properties Toggle Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plain `+`/`−` text toggle in `ManualMatchPage`'s Reference ID cell with solid circled plus/minus icons for visual prominence.

**Architecture:** Add `plusCircle`/`minusCircle` to the existing `Icons.scala` inline-SVG icon component, following its established `icon(svgStr: String): HtmlElement` helper and Heroicons-solid style. Swap them in for the text glyphs in `ManualMatchPage.referenceIdCell`.

**Tech Stack:** Scala.js/Laminar frontend.

## Global Constraints

- No backend changes.
- Follow `Icons.scala`'s existing pattern exactly (private `icon(svgStr)` helper, `val name: HtmlElement = icon("""...""")`) — don't introduce a second icon-rendering mechanism.

---

### Task 1: Add `plusCircle`/`minusCircle` icons and use them in the toggle

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/components/Icons.scala` (add two `val`s)
- Modify: `frontend/src/main/scala/perfectpair/view/pages/ManualMatchPage.scala` (import `Icons`, update `referenceIdCell`)

**Interfaces:**
- Consumes: `Icons.icon(svgStr: String): HtmlElement` (pre-existing private helper in the same object — the new vals just call it, same as every other icon in the file).
- Produces: `Icons.plusCircle: HtmlElement`, `Icons.minusCircle: HtmlElement` — consumed by `ManualMatchPage.referenceIdCell`.

- [ ] **Step 1: Add the two icons to `Icons.scala`**

In `frontend/src/main/scala/perfectpair/view/components/Icons.scala`, add (anywhere among the other `val`s, e.g. after `lock`):

```scala
  val plusCircle: HtmlElement = icon(
    """<svg class="w-5 h-5 text-md-primary" aria-hidden="true" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm.75-11.25a.75.75 0 00-1.5 0v2.5h-2.5a.75.75 0 000 1.5h2.5v2.5a.75.75 0 001.5 0v-2.5h2.5a.75.75 0 000-1.5h-2.5v-2.5z" clip-rule="evenodd"/></svg>"""
  )

  val minusCircle: HtmlElement = icon(
    """<svg class="w-5 h-5 text-md-primary" aria-hidden="true" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM7 9a1 1 0 000 2h6a1 1 0 100-2H7z" clip-rule="evenodd"/></svg>"""
  )
```

- [ ] **Step 2: Use the icons in `ManualMatchPage.referenceIdCell`**

In `frontend/src/main/scala/perfectpair/view/pages/ManualMatchPage.scala`, change the import:

```scala
import perfectpair.view.components.{Buttons, Table}
```

to:

```scala
import perfectpair.view.components.{Buttons, Icons, Table}
```

Then replace the toggle button inside `referenceIdCell`:

```scala
        button(
          cls := "text-xs w-4 text-md-primary hover:underline",
          typ := "button",
          onClick.mapToUnit --> Observer[Unit](_ =>
            state.update(st => st.copy(expandedProperties = toggle(st.expandedProperties, m.id)))
          ),
          child.text <-- state.signal.map(s => if s.expandedProperties.contains(m.id) then "−" else "+")
        ),
```

with:

```scala
        button(
          cls := "cursor-pointer",
          typ := "button",
          onClick.mapToUnit --> Observer[Unit](_ =>
            state.update(st => st.copy(expandedProperties = toggle(st.expandedProperties, m.id)))
          ),
          child <-- state.signal.map(s => if s.expandedProperties.contains(m.id) then Icons.minusCircle else Icons.plusCircle)
        ),
```

- [ ] **Step 3: Compile the frontend**

Run: `sbt frontend/compile`
Expected: `[success]` with no errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/components/Icons.scala frontend/src/main/scala/perfectpair/view/pages/ManualMatchPage.scala
git commit -m "Use plus/minus circle icons for properties toggle"
```

---

### Task 2: Manual verification

**Files:** none

- [ ] **Step 1:** With the app running, open Manual Match for an account with unreconciled movements. Confirm the Reference ID cell shows a solid circled plus icon instead of a `+` character.
- [ ] **Step 2:** Click it — confirm it swaps to a solid circled minus icon and the properties table still expands/collapses correctly.
- [ ] **Step 3:** No commit needed unless a bug is found.
