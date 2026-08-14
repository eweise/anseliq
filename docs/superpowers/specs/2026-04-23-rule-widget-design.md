# RuleWidget Design Spec

## Goal

Add a `RuleWidget` component to `MaintainReconSpecView` that allows users to configure the `rule` field of a `ReconciliationSpecPayload`. Supports three leaf/composite rule types: `StringAttributeMatchRule`, `DateAttributeMatchRule`, and `AndRule` (containing only leaf rules).

## Architecture

Extract rule editing into a standalone `RuleWidget` object following the existing `DataSourceConfigWidget` pattern. `MaintainReconSpecView` delegates to it via a `ruleWidget: RuleWidget.Model` field and a `RuleWidgetMsg` message case. The widget renders as a self-contained section at the bottom of the recon spec form.

## New File

**`frontend/src/main/scala/onparity/view/components/RuleWidget.scala`**

### Model

```scala
case class LeafModel(
  ruleType: String = "StringMatch",  // "StringMatch" | "DateMatch"
  inKey:    String = "",
  outKey:   String = ""
)

case class Model(
  ruleType:  String          = "NoOp",  // "NoOp" | "StringMatch" | "DateMatch" | "And"
  inKey:     String          = "",
  outKey:    String          = "",
  andRules:  Seq[LeafModel]  = Seq()
)
```

### Msg

```
SelectRuleType(ruleType: String)
UpdateInKey(key: String)
UpdateOutKey(key: String)
AddAndRule
RemoveAndRule(index: Int)
UpdateAndRuleType(index: Int, ruleType: String)
UpdateAndRuleInKey(index: Int, key: String)
UpdateAndRuleOutKey(index: Int, key: String)
```

### Helpers

- `fromRule(rule: Rule): Model` — converts a domain `Rule` to widget `Model` for edit mode
- `toRule(model: Model): Rule` — converts widget `Model` back to a domain `Rule` for saving
- `columnsFor(specId: String, specs: Seq[MovementSpecPayload]): Seq[String]` — finds the matching spec, extracts `CSVParserConfig.columnDefinitions.values.toSeq`; returns `Seq.empty` if no match or config is absent

### Rendering

The widget renders a labeled section titled "Matching Rule":

1. **Rule type dropdown** — options: No Rule, String Match, Date Match, And
2. If **StringMatch** or **DateMatch** selected: two column dropdowns (In Key, Out Key) populated from the respective movement spec's column definitions
3. If **And** selected: a list of sub-rule rows, each with a type dropdown + In Key + Out Key dropdowns + a Remove button; an "Add Rule" button below the list appends a new `LeafModel` with defaults

## Changes to Existing Files

### `MaintainReconSpecView.scala`

- Add `ruleWidget: RuleWidget.Model = RuleWidget.Model()` to `Model`
- Add `RuleWidgetMsg(msg: RuleWidget.Msg)` to `Msg` enum
- In `update`, handle `RuleWidgetMsg` by delegating to `RuleWidget.update`
- In `Create` and `Update` handlers, copy rule onto spec before sending: `spec.copy(rule = RuleWidget.toRule(model.ruleWidget))`
- Render `RuleWidget(model.ruleWidget, model.movementSpecs, spec.inMovementSpecId, spec.outMovementSpecId, ...)` after the existing form fields, before the action buttons

### `WebApp.scala`

- In `NavigateToMaintainReconSpec` handler: initialize `ruleWidget = RuleWidget.fromRule(reconSpec.map(_.rule).getOrElse(NoOpRule))` when building `MaintainReconSpecView.Model`

## Constraints

- `StringAttribute` and `DateAttribute` are always created with `modifier = None` (no modifier UI)
- `CSVParserConfig` is the only supported `ParserConfig` variant; other variants produce empty column lists
- AndRule sub-rules are only leaf types (StringMatch or DateMatch) — no nested AndRules
- `NoOpRule` is the default when no rule type is selected
