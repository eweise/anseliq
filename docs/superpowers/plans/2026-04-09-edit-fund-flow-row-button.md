# Edit Fund Flow Row Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Edit" button to each row in `FundFlowsView` that navigates to `MaintainFundFlowView` with that fund flow pre-populated.

**Architecture:** Add a new `Msg.SelectFundFlow` message that sets `selectedFundFlow` in the model and navigates to `Page.MaintainFundFlow`, following the same pattern as `GetAccountsForFundFlow`. Wire it up in the update function and add the button column to the view.

**Tech Stack:** Scala.js, Tyrian (Elm-like UI framework), Tailwind CSS

---

### Task 1: Add `SelectFundFlow` message to `Msg` enum

**Files:**
- Modify: `frontend/src/main/scala/onparity/model/model.scala`

- [ ] **Step 1: Add the new message case**

In `frontend/src/main/scala/onparity/model/model.scala`, add `SelectFundFlow` after `FundFlowCreated`:

```scala
  case FundFlowCreated(fundFlow:FundFlowPayload)
  case SelectFundFlow(fundFlow: FundFlowPayload)
  case Logout
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/eweise/projects/onparity
sbt frontend/compile
```

Expected: `[success]` with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/model/model.scala
git commit -m "feat: add SelectFundFlow message to Msg enum"
```

---

### Task 2: Handle `SelectFundFlow` in the update function

**Files:**
- Modify: `frontend/src/main/scala/onparity/main/WebApp.scala`

- [ ] **Step 1: Add the update case**

In `frontend/src/main/scala/onparity/main/WebApp.scala`, add a handler after the `FundFlowCreated` case (around line 124):

```scala
    case Msg.FundFlowCreated(ff) =>
      (model.modify(_.fundFlows).setTo(Seq(ff)), PrettyLogger.info("created model with fund flow"))

    case Msg.SelectFundFlow(ff) =>
      (
        model.modify(_.selectedFundFlow).setTo(Some(ff)),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainFundFlow))
      )
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/eweise/projects/onparity
sbt frontend/compile
```

Expected: `[success]` with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/main/WebApp.scala
git commit -m "feat: handle SelectFundFlow — set selectedFundFlow and navigate to MaintainFundFlow"
```

---

### Task 3: Add "Edit" button column to `FundFlowsView`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/FundFlowsView.scala`

- [ ] **Step 1: Update the view to add the Edit column**

Replace the contents of `frontend/src/main/scala/onparity/view/pages/FundFlowsView.scala` with:

```scala
package onparity.view.pages

import onparity.common.model.FundFlowPayload
import onparity.model.Msg
import onparity.page.Page
import onparity.view.components.{Buttons, Table}
import tyrian.Html
import tyrian.Html.*

import java.time.format.DateTimeFormatter

object FundFlowsView:

  def apply(fundFlows: Seq[FundFlowPayload]): Html[Msg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "w-full max-w-4xl px-4")(
        div(cls := "flex justify-between items-center my-8")(
          h2(cls := "text-xl font-medium")(text("Fund Flows")),
          Buttons.linkButton("Create", Msg.NavigateTo(Page.MaintainFundFlow))
        ),
        Table.create(
          Seq("ID", "Name", "Created At", "Updated At", ""),
          fundFlows.map(ff =>
            Seq(
              text(ff.id),
              text(ff.name),
              text(ff.createdAt.get.toString),
              text(ff.updatedAt.get.toString),
              Buttons.textButton("Edit", Msg.SelectFundFlow(ff))
            )
          )
        )
      )
    )
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/eweise/projects/onparity
sbt frontend/compile
```

Expected: `[success]` with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/FundFlowsView.scala
git commit -m "feat: add Edit button per row in FundFlowsView"
```
