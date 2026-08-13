# RuleWidget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `RuleWidget` component to `MaintainReconSpecView` for configuring the `rule` field of a `ReconciliationSpecPayload`, supporting `StringAttributeMatchRule`, `DateAttributeMatchRule`, and `AndRule` (flat — leaf rules only).

**Architecture:** `RuleWidget` is a self-contained component following the `DataSourceConfigWidget` pattern — its own `Model`/`Msg`/`update`/`apply`, `fromRule`/`toRule` helpers for domain conversion, and a `columnsFor` helper that reads column names from a `MovementSpecPayload`'s `CSVParserConfig`. `MaintainReconSpecView` delegates to it via a `ruleWidget` model field and `RuleWidgetMsg` case. `WebApp` initialises the widget from the existing spec's rule when navigating to the page.

**Tech Stack:** Scala 3, Scala.js, Tyrian, upickle, Tailwind CSS (Material Design tokens)

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `frontend/src/main/scala/perfectpair/view/components/RuleWidget.scala` | **Create** | Widget Model/Msg/update/apply, fromRule, toRule, columnsFor |
| `frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala` | Modify | Add `ruleWidget` field, `RuleWidgetMsg`, delegation, rule on save |
| `frontend/src/main/scala/perfectpair/main/WebApp.scala` | Modify | Initialise `ruleWidget` from existing spec rule in `NavigateToMaintainReconSpec` |

---

## Task 1: Create RuleWidget.scala

**Files:**
- Create: `frontend/src/main/scala/perfectpair/view/components/RuleWidget.scala`

- [ ] **Step 1: Create the file**

```scala
package perfectpair.view.components

import perfectpair.common.model.*
import tyrian.EmptyAttribute
import tyrian.Html.*
import tyrian.Html

object RuleWidget:

  case class LeafModel(
    ruleType: String = "StringMatch",
    inKey:    String = "",
    outKey:   String = ""
  )

  case class Model(
    ruleType: String         = "NoOp",
    inKey:    String         = "",
    outKey:   String         = "",
    andRules: Seq[LeafModel] = Seq()
  )

  enum Msg:
    case SelectRuleType(ruleType: String)
    case UpdateInKey(key: String)
    case UpdateOutKey(key: String)
    case AddAndRule
    case RemoveAndRule(index: Int)
    case UpdateAndRuleType(index: Int, ruleType: String)
    case UpdateAndRuleInKey(index: Int, key: String)
    case UpdateAndRuleOutKey(index: Int, key: String)

  def update(model: Model, msg: Msg): Model =
    msg match
      case Msg.SelectRuleType(rt)          => model.copy(ruleType = rt)
      case Msg.UpdateInKey(key)            => model.copy(inKey = key)
      case Msg.UpdateOutKey(key)           => model.copy(outKey = key)
      case Msg.AddAndRule                  => model.copy(andRules = model.andRules :+ LeafModel())
      case Msg.RemoveAndRule(i)            => model.copy(andRules = model.andRules.patch(i, Nil, 1))
      case Msg.UpdateAndRuleType(i, rt)    => model.copy(andRules = model.andRules.updated(i, model.andRules(i).copy(ruleType = rt)))
      case Msg.UpdateAndRuleInKey(i, key)  => model.copy(andRules = model.andRules.updated(i, model.andRules(i).copy(inKey = key)))
      case Msg.UpdateAndRuleOutKey(i, key) => model.copy(andRules = model.andRules.updated(i, model.andRules(i).copy(outKey = key)))

  def fromRule(rule: Rule): Model =
    rule match
      case NoOpRule =>
        Model()
      case StringAttributeMatchRule(inKey, outKey) =>
        Model(ruleType = "StringMatch", inKey = inKey.key, outKey = outKey.key)
      case DateAttributeMatchRule(inKey, outKey) =>
        Model(ruleType = "DateMatch", inKey = inKey.key, outKey = outKey.key)
      case AndRule(rules) =>
        val leaves = rules.collect {
          case StringAttributeMatchRule(inKey, outKey) => LeafModel("StringMatch", inKey.key, outKey.key)
          case DateAttributeMatchRule(inKey, outKey)   => LeafModel("DateMatch",   inKey.key, outKey.key)
        }
        Model(ruleType = "And", andRules = leaves)
      case _ => Model()

  def toRule(model: Model): Rule =
    model.ruleType match
      case "StringMatch" =>
        StringAttributeMatchRule(StringAttribute(model.inKey), StringAttribute(model.outKey))
      case "DateMatch" =>
        DateAttributeMatchRule(DateAttribute(model.inKey), DateAttribute(model.outKey))
      case "And" =>
        AndRule(model.andRules.toList.map { leaf =>
          if leaf.ruleType == "DateMatch" then
            DateAttributeMatchRule(DateAttribute(leaf.inKey), DateAttribute(leaf.outKey))
          else
            StringAttributeMatchRule(StringAttribute(leaf.inKey), StringAttribute(leaf.outKey))
        })
      case _ => NoOpRule

  def columnsFor(specId: String, specs: Seq[MovementSpecPayload]): Seq[String] =
    specs
      .find(_.id.contains(specId))
      .flatMap(_.dataSourceConfig)
      .collect { case DataSourceConfig(CSVParserConfig(_, cols, _)) => cols.values.toSeq }
      .getOrElse(Seq.empty)

  private val ruleTypeOptions: Seq[(String, String)] = Seq(
    "NoOp"        -> "No Rule",
    "StringMatch" -> "String Match",
    "DateMatch"   -> "Date Match",
    "And"         -> "And"
  )

  private val leafRuleTypeOptions: Seq[(String, String)] = Seq(
    "StringMatch" -> "String Match",
    "DateMatch"   -> "Date Match"
  )

  private def selectField[M](
    fieldID:  String,
    label:    String,
    selected: String,
    options:  Seq[(String, String)],
    onSelect: String => M
  ): Html[M] =
    div(cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded")(
      tyrian.Html.label(
        cls := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant",
        attribute("for", fieldID)
      )(text(label)),
      select(
        id  := fieldID,
        cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onChange(onSelect)
      )(
        options.map { case (v, lbl) =>
          option(
            value := v,
            if v == selected then attribute("selected", "selected") else EmptyAttribute
          )(text(lbl))
        }*
      )
    )

  private def leafRow[M](
    index:   Int,
    leaf:    LeafModel,
    inCols:  Seq[String],
    outCols: Seq[String],
    lift:    Msg => M
  ): Html[M] =
    div(cls := "border border-md-outline rounded p-3 mb-3")(
      div(cls := "flex justify-between items-center mb-2")(
        p(cls := "text-sm font-medium text-md-on-surface")(text(s"Rule ${index + 1}")),
        button(
          cls     := "text-md-error text-sm px-2",
          tpe     := "button",
          onClick(lift(Msg.RemoveAndRule(index)))
        )(text("Remove"))
      ),
      selectField(s"andType_$index",   "Type",    leaf.ruleType, leafRuleTypeOptions,           v => lift(Msg.UpdateAndRuleType(index, v))),
      selectField(s"andInKey_$index",  "In Key",  leaf.inKey,    inCols.map(c => c -> c),       v => lift(Msg.UpdateAndRuleInKey(index, v))),
      selectField(s"andOutKey_$index", "Out Key", leaf.outKey,   outCols.map(c => c -> c),      v => lift(Msg.UpdateAndRuleOutKey(index, v)))
    )

  def apply[M](
    model:             Model,
    movementSpecs:     Seq[MovementSpecPayload],
    inMovementSpecId:  String,
    outMovementSpecId: String,
    lift:              Msg => M
  ): Html[M] =
    val inCols  = columnsFor(inMovementSpecId, movementSpecs)
    val outCols = columnsFor(outMovementSpecId, movementSpecs)
    div(cls := "border-t border-md-outline pt-5 mt-2")(
      p(cls := "text-sm font-medium text-md-on-surface mb-4")(text("Matching Rule")),
      selectField("ruleType", "Rule Type", model.ruleType, ruleTypeOptions, v => lift(Msg.SelectRuleType(v))),
      model.ruleType match
        case "StringMatch" | "DateMatch" =>
          div()(
            selectField("inKey",  "In Key",  model.inKey,  inCols.map(c => c -> c),  v => lift(Msg.UpdateInKey(v))),
            selectField("outKey", "Out Key", model.outKey, outCols.map(c => c -> c), v => lift(Msg.UpdateOutKey(v)))
          )
        case "And" =>
          div()(
            div()(model.andRules.zipWithIndex.map { case (leaf, i) =>
              leafRow(i, leaf, inCols, outCols, lift)
            }*),
            button(
              cls     := "text-md-primary text-sm px-4 py-2 rounded-full border border-md-primary mt-1",
              tpe     := "button",
              onClick(lift(Msg.AddAndRule))
            )(text("+ Add Rule"))
          )
        case _ => div()()
    )
```

- [ ] **Step 2: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS (warnings about pre-existing non-exhaustive matches are fine)

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/components/RuleWidget.scala
git commit -m "feat: add RuleWidget component for rule selection and configuration"
```

---

## Task 2: Wire RuleWidget into MaintainReconSpecView

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala`

Four changes: import, Msg case, update handler, model field, apply render, save logic.

- [ ] **Step 1: Add RuleWidget import**

Find:
```scala
import perfectpair.view.components.{Buttons, Form}
```

Replace with:
```scala
import perfectpair.view.components.{Buttons, Form, RuleWidget}
```

- [ ] **Step 2: Add RuleWidgetMsg to the Msg enum**

Find:
```scala
  enum Msg:
    case FormUpdate(spec: ReconciliationSpecPayload)
    case ReconSpecAction(spec: ReconciliationSpecPayload, action: PayloadAction)
```

Replace with:
```scala
  enum Msg:
    case FormUpdate(spec: ReconciliationSpecPayload)
    case ReconSpecAction(spec: ReconciliationSpecPayload, action: PayloadAction)
    case RuleWidgetMsg(msg: RuleWidget.Msg)
```

- [ ] **Step 3: Add RuleWidgetMsg handler and inject rule on save in update**

Replace the entire `update` method:

```scala
  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FormUpdate(spec) =>
        (model.copy(reconSpec = Some(spec)), Cmd.None)
      case Msg.RuleWidgetMsg(widgetMsg) =>
        (model.copy(ruleWidget = RuleWidget.update(model.ruleWidget, widgetMsg)), Cmd.None)
      case Msg.ReconSpecAction(spec, Create) =>
        val toSave = spec.copy(rule = RuleWidget.toRule(model.ruleWidget))
        (model, HttpHelper.createReconSpec(toSave))
      case Msg.ReconSpecAction(spec, Created) =>
        (model.copy(reconSpec = Some(spec)), Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))
      case Msg.ReconSpecAction(spec, Update) =>
        val toSave = spec.copy(rule = RuleWidget.toRule(model.ruleWidget))
        (model, HttpHelper.updateReconSpec(toSave))
      case Msg.ReconSpecAction(spec, Updated) =>
        (model.copy(reconSpec = Some(spec)), Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))
      case Msg.ReconSpecAction(_, Delete) =>
        val id = model.reconSpec.flatMap(_.id).getOrElse("")
        (model, HttpHelper.deleteReconSpec(id))
      case Msg.ReconSpecAction(_, Deleted) =>
        (model, Cmd.emit(AppMsg.NavigateToReconSpecs(model.account)))
```

- [ ] **Step 4: Add ruleWidget field to Model**

Replace:
```scala
  case class Model(
    account:       AccountPayload                    = AccountPayload(),
    movementSpecs: Seq[MovementSpecPayload]          = Seq(),
    reconSpec:     Option[ReconciliationSpecPayload] = None
  )
```

With:
```scala
  case class Model(
    account:       AccountPayload                    = AccountPayload(),
    movementSpecs: Seq[MovementSpecPayload]          = Seq(),
    reconSpec:     Option[ReconciliationSpecPayload] = None,
    ruleWidget:    RuleWidget.Model                  = RuleWidget.Model()
  )
```

- [ ] **Step 5: Render RuleWidget in apply**

Find the action buttons section (just before the closing `)`):
```scala
        div(cls := "flex justify-between mt-6")(
```

Insert `RuleWidget(...)` directly before that line:
```scala
        RuleWidget(
          model.ruleWidget,
          model.movementSpecs,
          spec.inMovementSpecId,
          spec.outMovementSpecId,
          msg => AppMsg.MaintainReconSpecMsg(Msg.RuleWidgetMsg(msg))
        ),
        div(cls := "flex justify-between mt-6")(
```

- [ ] **Step 6: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala
git commit -m "feat: wire RuleWidget into MaintainReconSpecView"
```

---

## Task 3: Initialise RuleWidget from existing rule in WebApp

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/main/WebApp.scala`

- [ ] **Step 1: Add RuleWidget import**

Find:
```scala
import perfectpair.view.components.DataSourceConfigWidget
```

Replace with:
```scala
import perfectpair.view.components.{DataSourceConfigWidget, RuleWidget}
```

- [ ] **Step 2: Add NoOpRule import**

Find:
```scala
import perfectpair.model.*
```

Add after it:
```scala
import perfectpair.common.model.NoOpRule
```

- [ ] **Step 3: Initialise ruleWidget in NavigateToMaintainReconSpec handler**

Find:
```scala
    case Msg.NavigateToMaintainReconSpec(account, movementSpecs, reconSpec) =>
      (
        model.copy(maintainReconSpecModel = MaintainReconSpecView.Model(
          account       = account,
          movementSpecs = movementSpecs,
          reconSpec     = reconSpec
        )),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainReconSpec))
      )
```

Replace with:
```scala
    case Msg.NavigateToMaintainReconSpec(account, movementSpecs, reconSpec) =>
      (
        model.copy(maintainReconSpecModel = MaintainReconSpecView.Model(
          account       = account,
          movementSpecs = movementSpecs,
          reconSpec     = reconSpec,
          ruleWidget    = RuleWidget.fromRule(reconSpec.map(_.rule).getOrElse(NoOpRule))
        )),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainReconSpec))
      )
```

- [ ] **Step 4: Verify it compiles**

```bash
sbt "frontend/fastOptJS"
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/main/scala/perfectpair/main/WebApp.scala
git commit -m "feat: initialise RuleWidget from existing rule on navigate to MaintainReconSpec"
```

---

## Self-Review

**Spec coverage:**
- ✅ StringAttributeMatchRule — `fromRule`/`toRule`/render in Task 1
- ✅ DateAttributeMatchRule — `fromRule`/`toRule`/render in Task 1
- ✅ AndRule (flat, leaf-only) — `fromRule`/`toRule`/`leafRow`/`AddAndRule`/`RemoveAndRule` in Task 1
- ✅ Keys from CSVParserConfig.columnDefinitions — `columnsFor` in Task 1
- ✅ inKey columns from inMovementSpec, outKey from outMovementSpec — `apply` params in Task 1
- ✅ modifier = None — `StringAttribute`/`DateAttribute` constructed without modifier in `toRule`
- ✅ ruleWidget field on MaintainReconSpecView.Model — Task 2 Step 4
- ✅ RuleWidgetMsg delegation — Task 2 Step 3
- ✅ rule injected on Create/Update — Task 2 Step 3
- ✅ ruleWidget initialised from existing spec on navigate — Task 3
- ✅ NoOpRule default — `RuleWidget.Model()` defaults to `ruleType = "NoOp"`; `toRule` returns `NoOpRule` for that case

**Placeholder scan:** None found.

**Type consistency:**
- `RuleWidget.Model` defined in Task 1, used as `MaintainReconSpecView.Model.ruleWidget` in Task 2 and `WebApp` in Task 3 ✅
- `RuleWidget.update` returns `Model` (not `(Model, Cmd)`) — matches `DataSourceConfigWidget` pattern ✅
- `RuleWidget.fromRule(rule: Rule): Model` used in Task 3 with `reconSpec.map(_.rule).getOrElse(NoOpRule)` ✅
- `RuleWidget.toRule(model: Model): Rule` used in Task 2 `Create`/`Update` handlers ✅
- `RuleWidget.columnsFor(specId: String, specs: Seq[MovementSpecPayload]): Seq[String]` used in `apply` ✅
- `Msg.RuleWidgetMsg(msg: RuleWidget.Msg)` defined Task 2 Step 2, handled Task 2 Step 3 ✅
