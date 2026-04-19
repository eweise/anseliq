# DataSourceConfig Widget Design

**Date:** 2026-04-18  
**Feature:** CSV Data Source configuration widget embedded in `MaintainMovementSpecsView`

---

## Overview

Add a `DataSourceConfigWidget` component to `MaintainMovementSpecsView` that lets users create and edit a `DataSourceConfig` (containing a `CSVParserConfig`). The widget has an enable/disable toggle — when disabled, the movement spec is saved with `dataSourceConfig = None`.

The widget is extracted as a reusable component to make it easy to add a JSON parser option in the future without modifying `MaintainMovementSpecsView`.

---

## New File

`frontend/src/main/scala/perfectpair/view/components/DataSourceConfigWidget.scala`

### Model

```scala
case class Model(
  enabled:        Boolean      = false,
  hasHeader:      Boolean      = true,
  columns:        List[String] = List(),  // index in list = CSV column number
  amountColIndex: Option[Int]  = None
)
```

Column indices are auto-assigned: position in `columns` list equals the CSV column number.

### Msg

```scala
enum Msg:
  case Toggle(enabled: Boolean)
  case SetHasHeader(value: Boolean)
  case AddColumn
  case UpdateColumn(index: Int, name: String)
  case RemoveColumn(index: Int)
  case SetAmountCol(index: Int)
```

### Conversion functions

```scala
def toDataSourceConfig(m: Model): Option[DataSourceConfig]
// Returns None when m.enabled == false
// Returns Some(DataSourceConfig(CSVParserConfig(...))) when enabled,
// building columnDefinitions as m.columns.zipWithIndex.map((name, i) => i -> name).toMap

def fromDataSourceConfig(cfg: Option[DataSourceConfig]): Model
// None  → Model() (disabled, defaults)
// Some(DataSourceConfig(CSVParserConfig(hasHeader, colDefs, amountField)))
//   → Model(enabled=true, hasHeader, columns sorted by key, amountColIndex=amountField)
```

### update

Pure function `(Model, Msg) => Model`. No commands emitted — all changes are local state.

- `Toggle(false)` resets to `Model()` (clears columns)
- `Toggle(true)` sets `enabled = true`, leaves other fields at defaults
- `RemoveColumn(i)` removes element at index `i` from `columns`; if `amountColIndex == Some(i)` clears it; adjusts any `amountColIndex > i` down by 1
- `AddColumn` appends `""` to `columns`

### apply (render)

Renders as an inline section at the bottom of the movement spec card:

1. A horizontal rule divider
2. Header row: label "CSV DATA SOURCE" + toggle switch (on = purple `#6750a4`, off = grey)
3. When enabled:
   - Checkbox: "Has Header Row"
   - Table with columns: **Col** (read-only index) | **Field Name** (text input) | **Amount?** (radio group) | **✕** (remove button)
   - "+ Add Column" dashed button below the table
4. Cancel / Save buttons remain in `MaintainMovementSpecsView` (not in this widget)

---

## Changes to `MaintainMovementSpecsView`

### Model

Add `dataSourceWidget: DataSourceConfigWidget.Model = DataSourceConfigWidget.Model()`.

When initialising from an existing spec (edit mode), set:
```scala
dataSourceWidget = DataSourceConfigWidget.fromDataSourceConfig(spec.dataSourceConfig)
```

### Msg

Add `case DataSourceMsg(msg: DataSourceConfigWidget.Msg)`.

### update

```scala
case Msg.DataSourceMsg(widgetMsg) =>
  (model.copy(dataSourceWidget = DataSourceConfigWidget.update(model.dataSourceWidget, widgetMsg)), Cmd.None)
```

On `MovementSpecAction(spec, Create | Update)`, build the payload as:
```scala
val specToSave = spec.copy(
  dataSourceConfig = DataSourceConfigWidget.toDataSourceConfig(model.dataSourceWidget)
)
```

### apply (render)

Embed `DataSourceConfigWidget(model.dataSourceWidget, msg => AppMsg.MaintainMovementSpecsMsg(Msg.DataSourceMsg(msg)))` below the Out Account selector, before the Cancel/Save buttons.

---

## Behaviour Notes

- Toggling off and back on resets the CSV fields (blank state), not the previously entered values. This is acceptable for now.
- The Amount? radio group uses a single named group so only one column can be selected as the amount field at a time.
- When a column is removed, the indices of subsequent columns shift down automatically (because indices are derived from list position), so `amountColIndex` must be adjusted accordingly.

---

## Out of Scope

- JSON parser config (future work — widget structure is designed to accommodate it)
- Validation (empty field names, no amount column selected)
- Persistence of toggle-off state while keeping previously entered CSV fields
