# DataSourceConfig Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `DataSourceConfigWidget` component to `MaintainMovementSpecsView` that lets users configure a CSV data source (hasHeader, column definitions, amount field) with an enable/disable toggle.

**Architecture:** A new `DataSourceConfigWidget` object with its own `Model`, `Msg`, and pure `update` function is created in `frontend/src/main/scala/perfectpair/view/components/`. `MaintainMovementSpecsView` embeds the widget's Model, delegates messages to it, and converts its state to/from `DataSourceConfig` on save and edit-load. `WebApp.scala` is updated to pre-populate the widget when navigating to edit an existing spec.

**Tech Stack:** Scala 3, Scala.js, Tyrian (Elm-style reactive UI), Tailwind CSS. No test framework exists for the frontend — verification is via `sbt "frontend/fastOptJS"` compile and browser visual check.

---

## File Map

| Action | File |
|--------|------|
| **Create** | `frontend/src/main/scala/perfectpair/view/components/DataSourceConfigWidget.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/view/pages/MaintainMovementSpecsView.scala` |
| **Modify** | `frontend/src/main/scala/perfectpair/main/WebApp.scala` |

---

### Task 1: DataSourceConfigWidget — Model, Msg, update, and conversion functions

**Files:**
- Create: `frontend/src/main/scala/perfectpair/view/components/DataSourceConfigWidget.scala`

- [ ] **Step 1: Create the file with Model, Msg, update, and conversion functions**

Create `frontend/src/main/scala/perfectpair/view/components/DataSourceConfigWidget.scala` with this exact content:

```scala
package perfectpair.view.components

import perfectpair.common.model.{CSVParserConfig, DataSourceConfig}
import tyrian.EmptyAttribute
import tyrian.Html.*
import tyrian.Html

object DataSourceConfigWidget:

  case class Model(
    enabled:        Boolean      = false,
    hasHeader:      Boolean      = true,
    columns:        List[String] = List(),
    amountColIndex: Option[Int]  = None
  )

  enum Msg:
    case Toggle(enabled: Boolean)
    case SetHasHeader(value: Boolean)
    case AddColumn
    case UpdateColumn(index: Int, name: String)
    case RemoveColumn(index: Int)
    case SetAmountCol(index: Int)

  def update(model: Model, msg: Msg): Model =
    msg match
      case Msg.Toggle(false)       => Model()
      case Msg.Toggle(true)        => model.copy(enabled = true)
      case Msg.SetHasHeader(v)     => model.copy(hasHeader = v)
      case Msg.AddColumn           => model.copy(columns = model.columns :+ "")
      case Msg.UpdateColumn(i, v)  => model.copy(columns = model.columns.updated(i, v))
      case Msg.SetAmountCol(i)     => model.copy(amountColIndex = Some(i))
      case Msg.RemoveColumn(i)     =>
        val newCols = model.columns.patch(i, Nil, 1)
        val newAmt  = model.amountColIndex.flatMap { idx =>
          if idx == i then None
          else if idx > i then Some(idx - 1)
          else Some(idx)
        }
        model.copy(columns = newCols, amountColIndex = newAmt)

  def toDataSourceConfig(m: Model): Option[DataSourceConfig] =
    if !m.enabled then None
    else Some(DataSourceConfig(
      parserConfig = CSVParserConfig(
        hasHeader         = m.hasHeader,
        columnDefinitions = m.columns.zipWithIndex.map((name, i) => i -> name).toMap,
        amountField       = m.amountColIndex
      )
    ))

  def fromDataSourceConfig(cfg: Option[DataSourceConfig]): Model =
    cfg match
      case None => Model()
      case Some(DataSourceConfig(CSVParserConfig(hasHeader, colDefs, amountField))) =>
        Model(
          enabled        = true,
          hasHeader      = hasHeader,
          columns        = colDefs.toList.sortBy(_._1).map(_._2),
          amountColIndex = amountField
        )

  def apply[M](model: Model, lift: Msg => M): Html[M] = Html.empty
```

The `apply` body is a placeholder (`Html.empty`) — it will be filled in Task 2.

- [ ] **Step 2: Verify it compiles**

Run from the project root:
```bash
sbt "frontend/fastOptJS"
```
Expected: BUILD SUCCESS with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/components/DataSourceConfigWidget.scala
git commit -m "feat: add DataSourceConfigWidget skeleton with Model, Msg, update, and conversions"
```

---

### Task 2: DataSourceConfigWidget — render function

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/components/DataSourceConfigWidget.scala`

- [ ] **Step 1: Replace the `apply` placeholder with the full render function**

Replace the entire `def apply[M](model: Model, lift: Msg => M): Html[M] = Html.empty` line with:

```scala
  def apply[M](model: Model, lift: Msg => M): Html[M] =
    div()(
      hr(cls := "my-4 border-md-outline-variant"),
      div(cls := "flex items-center justify-between mb-3")(
        span(cls := "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant")(
          text("CSV Data Source")
        ),
        div(
          cls     := s"flex items-center gap-2 cursor-pointer",
          onClick(lift(Msg.Toggle(!model.enabled)))
        )(
          div(cls := s"w-9 h-5 rounded-full relative transition-colors ${if model.enabled then "bg-md-primary" else "bg-gray-300"}")(
            div(cls := s"w-4 h-4 bg-white rounded-full absolute top-0.5 shadow-sm transition-all ${if model.enabled then "right-0.5" else "left-0.5"}")()
          ),
          span(cls := s"text-xs font-medium ${if model.enabled then "text-md-primary" else "text-gray-400"}")(
            text(if model.enabled then "On" else "Off")
          )
        )
      ),
      if model.enabled then
        div()(
          div(
            cls     := "flex items-center gap-2 mb-4 cursor-pointer",
            onClick(lift(Msg.SetHasHeader(!model.hasHeader)))
          )(
            div(cls := s"w-4 h-4 rounded border flex items-center justify-center ${if model.hasHeader then "bg-md-primary border-md-primary" else "border-gray-400"}")(
              if model.hasHeader then span(cls := "text-white text-xs leading-none")(text("✓")) else span()()
            ),
            span(cls := "text-sm text-md-on-surface")(text("Has Header Row"))
          ),
          if model.columns.nonEmpty then
            div(cls := "mb-3 border border-md-outline rounded overflow-hidden")(
              table(cls := "w-full text-xs border-collapse")(
                thead(cls := "bg-md-surface-variant")(
                  tr()(
                    th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium w-10")(text("Col")),
                    th(cls := "text-left px-2 py-2 text-md-on-surface-variant font-medium")(text("Field Name")),
                    th(cls := "text-center px-2 py-2 text-md-on-surface-variant font-medium w-14")(text("Amount?")),
                    th(cls := "w-6")()
                  )
                ),
                tbody()(
                  model.columns.zipWithIndex.map { (colName, idx) =>
                    tr(cls := "border-t border-md-outline-variant")(
                      td(cls := "px-2 py-1.5 text-md-on-surface-variant font-medium")(text(idx.toString)),
                      td(cls := "px-2 py-1.5")(
                        input(
                          tpe   := "text",
                          cls   := "w-full border border-md-outline rounded px-2 py-1 text-xs focus:outline-none focus:border-md-primary",
                          attribute("value", colName),
                          onChange(v => lift(Msg.UpdateColumn(idx, v)))
                        )
                      ),
                      td(cls := "text-center px-2 py-1.5")(
                        input(
                          tpe  := "radio",
                          name := "amountCol",
                          cls  := "accent-md-primary",
                          if model.amountColIndex.contains(idx) then attribute("checked", "checked") else EmptyAttribute,
                          onChange(_ => lift(Msg.SetAmountCol(idx)))
                        )
                      ),
                      td(cls := "px-1 py-1.5 text-center")(
                        button(
                          tpe := "button",
                          cls := "text-gray-300 hover:text-md-error",
                          onClick(lift(Msg.RemoveColumn(idx)))
                        )(text("✕"))
                      )
                    )
                  }*
                )
              )
            )
          else div()(),
          button(
            tpe := "button",
            cls := "border border-dashed border-md-primary text-md-primary rounded px-3 py-1.5 text-xs hover:bg-md-primary-container transition-colors",
            onClick(lift(Msg.AddColumn))
          )(text("+ Add Column"))
        )
      else div()()
    )
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/components/DataSourceConfigWidget.scala
git commit -m "feat: implement DataSourceConfigWidget render function"
```

---

### Task 3: Integrate widget into MaintainMovementSpecsView

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/MaintainMovementSpecsView.scala`

- [ ] **Step 1: Add import and DataSourceMsg to Msg, dataSourceWidget to Model**

In `MaintainMovementSpecsView.scala`, make these changes:

1. Add import at the top (after existing imports):
```scala
import perfectpair.view.components.DataSourceConfigWidget
```

2. Replace the `enum Msg` block:
```scala
  enum Msg:
    case FormUpdate(spec: MovementSpecPayload)
    case MovementSpecAction(spec: MovementSpecPayload, action: PayloadAction)
    case DataSourceMsg(msg: DataSourceConfigWidget.Msg)
```

3. Replace the `case class Model` block:
```scala
  case class Model(
    fundFlow:         FundFlowPayload                = FundFlowPayload(),
    accounts:         Seq[AccountPayload]            = Seq(),
    movementSpec:     Option[MovementSpecPayload]    = None,
    dataSourceWidget: DataSourceConfigWidget.Model   = DataSourceConfigWidget.Model()
  )
```

- [ ] **Step 2: Update the `update` function to handle DataSourceMsg and attach dataSourceConfig on save**

Replace the entire `def update` method with:

```scala
  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FormUpdate(spec) =>
        (model.copy(movementSpec = Some(spec)), PrettyLogger.info("movement spec form modified"))
      case Msg.DataSourceMsg(widgetMsg) =>
        (model.copy(dataSourceWidget = DataSourceConfigWidget.update(model.dataSourceWidget, widgetMsg)), Cmd.None)
      case Msg.MovementSpecAction(spec, Create) =>
        val toSave = spec.copy(dataSourceConfig = DataSourceConfigWidget.toDataSourceConfig(model.dataSourceWidget))
        (model, HttpHelper.createMovementSpec(toSave) |+| PrettyLogger.info("creating movement spec"))
      case Msg.MovementSpecAction(spec, Created) =>
        (model.copy(movementSpec = Some(spec)), PrettyLogger.info("created movement spec") |+| Cmd.emit(AppMsg.NavigateToMovementSpecs(model.fundFlow)))
      case Msg.MovementSpecAction(spec, Update) =>
        val toSave = spec.copy(dataSourceConfig = DataSourceConfigWidget.toDataSourceConfig(model.dataSourceWidget))
        (model, HttpHelper.updateMovementSpec(toSave) |+| PrettyLogger.info("updating movement spec"))
      case Msg.MovementSpecAction(spec, Updated) =>
        (model.copy(movementSpec = Some(spec)), PrettyLogger.info("updated movement spec") |+| Cmd.emit(AppMsg.NavigateToMovementSpecs(model.fundFlow)))
```

- [ ] **Step 3: Embed the widget in the `apply` render function**

In `def apply(model: Model): Html[AppMsg]`, add the widget call between the Out Account select and the Cancel/Save button row. Replace:

```scala
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", AppMsg.NavigateToMovementSpecs(model.fundFlow)),
          Buttons.linkButton("Save", AppMsg.MaintainMovementSpecsMsg(Msg.MovementSpecAction(spec, action)))
        )
```

with:

```scala
        DataSourceConfigWidget(model.dataSourceWidget, msg => AppMsg.MaintainMovementSpecsMsg(Msg.DataSourceMsg(msg))),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", AppMsg.NavigateToMovementSpecs(model.fundFlow)),
          Buttons.linkButton("Save", AppMsg.MaintainMovementSpecsMsg(Msg.MovementSpecAction(spec, action)))
        )
```

- [ ] **Step 4: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/MaintainMovementSpecsView.scala
git commit -m "feat: integrate DataSourceConfigWidget into MaintainMovementSpecsView"
```

---

### Task 4: Pre-populate widget on edit navigation

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/main/WebApp.scala`

- [ ] **Step 1: Update the NavigateToMaintainMovementSpec handler in WebApp.scala**

In `WebApp.scala`, replace:

```scala
    case Msg.NavigateToMaintainMovementSpec(fundFlow, accounts, movementSpec) =>
      (
        model.copy(maintainMovementSpecModel = MaintainMovementSpecsView.Model(fundFlow, accounts, movementSpec)),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainMovementSpec))
      )
```

with:

```scala
    case Msg.NavigateToMaintainMovementSpec(fundFlow, accounts, movementSpec) =>
      (
        model.copy(maintainMovementSpecModel = MaintainMovementSpecsView.Model(
          fundFlow         = fundFlow,
          accounts         = accounts,
          movementSpec     = movementSpec,
          dataSourceWidget = DataSourceConfigWidget.fromDataSourceConfig(movementSpec.flatMap(_.dataSourceConfig))
        )),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainMovementSpec))
      )
```

Also add the import at the top of `WebApp.scala` (after existing imports):

```scala
import perfectpair.view.components.DataSourceConfigWidget
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Visual test in browser**

Start the dev server:
```bash
sbt "frontend/devServer"
```

Open the app in the browser and:
1. Navigate to a Fund Flow → Movement Specs → create a new spec
2. Verify the CSV DATA SOURCE toggle appears below the account selects, defaulting to **Off**
3. Toggle it **On** — verify the Has Header Row checkbox and the empty column table appear
4. Click **+ Add Column** twice — verify two rows appear with auto-index 0 and 1
5. Type field names in each row (e.g. `record_date`, `amount`)
6. Click the **Amount?** radio on row 1 — verify only that row is selected
7. Click **✕** on row 0 — verify row 1 becomes row 0, and the Amount? radio stays on the (now-renamed) row
8. Click **Save** — verify the spec saves without error
9. Navigate back to the spec list and click edit on the saved spec — verify the widget pre-populates with the saved column config
10. Toggle **Off** and click Save — verify the spec saves with no data source config

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/main/WebApp.scala
git commit -m "feat: pre-populate DataSourceConfigWidget from existing spec on edit navigation"
```
