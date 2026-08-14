# Material Design 3 — MaintainFundFlow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply Material Design 3 styling (elevated card, outlined fields, pill buttons) to the MaintainFundFlow page by adding M3 color tokens to Tailwind and updating shared Form and Buttons components.

**Architecture:** Add 8 M3 semantic color tokens to `tailwind.config.js`, then update `Form.scala` and `Buttons.scala` to use those tokens with the M3 outlined field and pill button patterns. Wrap `MaintainFundFlowView.scala` in an elevated white card on a tinted surface background.

**Tech Stack:** Scala 3, Scala.js, Tyrian (Elm-like frontend), Tailwind CSS v3, SBT, Vite

---

## File Map

| File | Change |
|------|--------|
| `frontend/tailwind.config.js` | Add `md-*` color tokens under `theme.extend.colors` |
| `frontend/src/main/scala/onparity/view/components/Form.scala` | Replace classes with M3 outlined field pattern |
| `frontend/src/main/scala/onparity/view/components/Buttons.scala` | Replace `linkButton` with pill style; add `textButton` |
| `frontend/src/main/scala/onparity/view/pages/MaintainFundFlowView.scala` | Wrap in elevated card, add Cancel button |

---

## Task 1: Add M3 color tokens to Tailwind

**Files:**
- Modify: `frontend/tailwind.config.js`

- [ ] **Step 1: Open `tailwind.config.js` and add the M3 color tokens**

Replace the entire file with:

```js
module.exports = {
  content: [
    './src/main/scala/**/*.scala',
    "./node_modules/flowbite/**/*.js"
  ],
  theme: {
    extend: {
      colors: {
        'md-primary':             '#6750a4',
        'md-on-primary':          '#ffffff',
        'md-primary-container':   '#eaddff',
        'md-surface-variant':     '#ece6f0',
        'md-on-surface':          '#1c1b1f',
        'md-on-surface-variant':  '#49454f',
        'md-outline':             '#79747e',
        'md-error':               '#b3261e',
      }
    },
  },
  darkMode: 'selector',
  plugins: [
    require('flowbite/plugin')
  ],
}
```

- [ ] **Step 2: Verify Tailwind picks up the tokens**

```bash
cd frontend && npm run build 2>&1 | tail -5
```

Expected: build completes with no errors. The output CSS in `target/` will include the new color utilities.

- [ ] **Step 3: Commit**

```bash
git add frontend/tailwind.config.js
git commit -m "feat(frontend): add Material Design 3 color tokens to Tailwind"
```

---

## Task 2: Update Form.scala with M3 outlined field style

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/components/Form.scala`

The M3 outlined field places a floating label that sits on top of the top border edge using absolute positioning. The label always appears above (static float — no animation needed). An error state changes both the border and label to `md-error`.

- [ ] **Step 1: Replace `Form.scala` with the M3 implementation**

```scala
package onparity.view.components

import tyrian.Html
import tyrian.Html.*

object Form:
  def field[M](
    fieldID: String,
    labelName: String,
    onChangFn: String => M,
    maybeErrorMsg: Option[String] = None
  ): Html[M] =
    val hasError  = maybeErrorMsg.isDefined
    val borderCls = if hasError then "border-2 border-md-error"
                    else "border border-md-outline focus-within:border-2 focus-within:border-md-primary"
    val labelCls  = if hasError then "text-md-error" else "text-md-on-surface-variant"
    div(cls := s"relative mb-5 $borderCls rounded")(
      label(
        cls := s"absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide $labelCls"
      )(text(labelName)),
      input(
        id  := fieldID,
        cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onChange(onChangFn),
        required
      ),
      maybeErrorMsg
        .map(errMsg =>
          p(cls := "absolute -bottom-5 left-0 px-4 text-xs text-md-error")(text(errMsg))
        )
        .getOrElse(div()())
    )
```

Note: The border is on the outer `div` (not the `input`) so that `focus-within:` can upgrade it to 2px + primary color when the input inside is focused. The label's `bg-white` masks the border behind it, creating the M3 "notched outline" effect.

- [ ] **Step 2: Compile to verify no Scala errors**

```bash
cd /Users/eweise/projects/onparity && sbt "frontend/fastOptJS" 2>&1 | grep -E "(error|warning|success)" | tail -20
```

Expected: compilation succeeds with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/components/Form.scala
git commit -m "feat(frontend): update Form.field to Material Design 3 outlined style"
```

---

## Task 3: Update Buttons.scala with M3 pill buttons

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/components/Buttons.scala`

M3 buttons use fully-rounded pill shapes (`rounded-full`). Two variants:
- **`linkButton`** — filled (primary background, white text). Used for the primary action (Save).
- **`textButton`** — text only (primary colored text, transparent background). Used for secondary actions (Cancel).

- [ ] **Step 1: Replace `Buttons.scala` with the M3 implementation**

```scala
package onparity.view.components

import tyrian.Html
import tyrian.Html.*

object Buttons:
  def linkButton[M](title: String, msg: M): Html[M] =
    button(
      cls := "bg-md-primary text-md-on-primary rounded-full px-6 py-2.5 text-sm font-medium tracking-wide shadow-sm hover:bg-purple-800 transition-colors",
      onClick(msg)
    )(text(title))

  def textButton[M](title: String, msg: M): Html[M] =
    button(
      cls := "text-md-primary rounded-full px-6 py-2.5 text-sm font-medium tracking-wide hover:bg-md-primary-container transition-colors",
      onClick(msg)
    )(text(title))
```

- [ ] **Step 2: Compile to verify no Scala errors**

```bash
cd /Users/eweise/projects/onparity && sbt "frontend/fastOptJS" 2>&1 | grep -E "(error|warning|success)" | tail -20
```

Expected: compilation succeeds with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/components/Buttons.scala
git commit -m "feat(frontend): update Buttons to Material Design 3 pill style"
```

---

## Task 4: Wrap MaintainFundFlowView in an elevated card

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/MaintainFundFlowView.scala`

The page background uses `bg-md-surface-variant` (light purple-grey). The form sits inside a white card with rounded corners and a drop shadow. A "Fund Flow" heading sits above the fields. The actions row has Cancel (textButton) and Save (linkButton) right-aligned.

- [ ] **Step 1: Replace `MaintainFundFlowView.scala` with the elevated card layout**

```scala
package onparity.view.pages

import onparity.common.model.FundFlowPayload
import onparity.model.Msg
import onparity.page.Page
import onparity.view.components.{Buttons, Form}
import tyrian.Html
import tyrian.Html.*

object MaintainFundFlowView:
  def apply(ff: FundFlowPayload): Html[Msg] =
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Fund Flow")),
        Form.field(
          fieldID    = "name",
          labelName  = "Name",
          onChangFn  = value => Msg.FundFlowFormUpdate(ff.copy(name = value))
        ),
        Form.field(
          fieldID   = "aging-limit",
          labelName = "Aging Limit",
          onChangFn = value => Msg.FundFlowFormUpdate(ff.copy(name = value))
        ),
        Form.field(
          fieldID   = "account1",
          labelName = "First Data Source",
          onChangFn = _ => Msg.FundFlowFormUpdate(ff)
        ),
        Form.field(
          fieldID   = "account2",
          labelName = "Second Data Source",
          onChangFn = _ => Msg.FundFlowFormUpdate(ff)
        ),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", Msg.NavigateTo(Page.FundFlows)),
          Buttons.linkButton("Save", Msg.CreateFundFlow(ff))
        )
      )
    )
```

- [ ] **Step 2: Compile to verify no Scala errors**

```bash
cd /Users/eweise/projects/onparity && sbt "frontend/fastOptJS" 2>&1 | grep -E "(error|warning|success)" | tail -20
```

Expected: compilation succeeds with no errors.

- [ ] **Step 3: Visual verification — start the dev server**

```bash
cd /Users/eweise/projects/onparity && sbt "frontend/devServer"
```

Open the browser and navigate to the MaintainFundFlow page. Verify:
- Page background is light purple-grey
- White card is centered with rounded corners and shadow
- "Fund Flow" heading appears above the fields
- All four fields show with outlined borders and floating labels
- Clicking into a field turns the border purple (2px)
- Save button is a filled purple pill; Cancel is a purple text button
- Clicking Cancel navigates back to the Fund Flows list

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/MaintainFundFlowView.scala
git commit -m "feat(frontend): apply Material Design 3 elevated card layout to MaintainFundFlow"
```
