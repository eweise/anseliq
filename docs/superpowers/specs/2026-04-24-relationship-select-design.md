# Relationship Field: Select Dropdown

## Summary

Replace the free-text `Form.field` for the `relationship` field in `MaintainReconSpecView` with a select dropdown populated from the `ReconRelationship` enum values.

## Changes

### `Form.scala` — add `Form.select`

Add a new `select` method to the `Form` object with the same visual styling as `Form.field` and the existing `movementSpecSelect` in `MaintainReconSpecView`. Signature:

```scala
def select[M](
  fieldID:      String,
  labelName:    String,
  options:      Seq[(String, String)],  // (value, label)
  selectedValue: String,
  onChangeFn:   String => M
): Html[M]
```

Renders a `<select>` inside the same floating-label wrapper used by `Form.field`.

### `MaintainReconSpecView.scala` — use `Form.select`

Replace the `Form.field` call for `relationship` with:

```scala
Form.select[AppMsg](
  fieldID       = "relationship",
  labelName     = "Relationship",
  options       = ReconRelationship.values.toSeq.map(r => (r.toString, r.toString)),
  selectedValue = spec.relationship.toString,
  onChangeFn    = value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(relationship = ReconRelationship.valueOf(value))))
)
```

## Out of Scope

- No changes to other views or the backend.
- No changes to how `ReconRelationship` values are serialized.
