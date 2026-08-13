# Relationship Select Dropdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the free-text Relationship input in `MaintainReconSpecView` with a styled select dropdown populated from the `ReconRelationship` enum.

**Architecture:** Add a generic `Form.select` method to the existing `Form` component (mirrors the styling of `Form.field`), then replace the `Form.field` call for `relationship` in `MaintainReconSpecView` with `Form.select`.

**Tech Stack:** Scala 3, Scala.js, Tyrian (functional UI), Tailwind CSS

---

## Files

- Modify: `frontend/src/main/scala/perfectpair/view/components/Form.scala` — add `select` method
- Modify: `frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala` — use `Form.select`

---

### Task 1: Add `Form.select` to the Form component

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/components/Form.scala`

- [ ] **Step 1: Add the `select` method**

Open `frontend/src/main/scala/perfectpair/view/components/Form.scala` and append the following method inside the `Form` object, after the existing `field` method:

```scala
  def select[M](
    fieldID:       String,
    labelName:     String,
    options:       Seq[(String, String)],
    selectedValue: String,
    onChangeFn:    String => M
  ): Html[M] =
    div(cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded")(
      label(
        cls := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant",
        attribute("for", fieldID)
      )(text(labelName)),
      tyrian.Html.select(
        id  := fieldID,
        cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onChange(onChangeFn)
      )(
        options.map { (value, label) =>
          option(
            tyrian.Html.value := value,
            if value == selectedValue then attribute("selected", "selected") else EmptyAttribute
          )(text(label))
        }*
      )
    )
```

The full `Form.scala` after the edit:

```scala
package perfectpair.view.components

import tyrian.Html.*
import tyrian.{Attr, EmptyAttribute, Html}

import java.time.OffsetDateTime

type FieldType = String | BigDecimal | OffsetDateTime

case class Field(value: FieldType)

object Form:

  def field[M](
    fieldID:       String,
    labelName:     String,
    defaultValue:  Option[FieldType]   = None,
    enabled:       Boolean             = true,
    onChangFn:     Option[String => M] = None,
    maybeErrorMsg: Option[String]      = None
  ): Html[M] =

    val valueAttrs: List[Attr[Nothing]] = defaultValue.map {
      case s:  String         => List(attribute("value", s), attribute("type", "text"))
      case b:  BigDecimal     => List(attribute("value", b.toString), attribute("type", "number"))
      case dt: OffsetDateTime => List(attribute("value", dt.toString), attribute("type", "datetime-local"))
    }
      .getOrElse(List())

    val allAttributes: List[Attr[M]] = valueAttrs ++ List(
      id := fieldID,
      if !enabled then attribute("disabled", "") else EmptyAttribute,
      cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
      onChangFn.map(f => onChange(f)).getOrElse(EmptyAttribute),
      required
    )

    val hasError = maybeErrorMsg.isDefined
    val borderCls =
      if hasError then "border-2 border-md-error"
      else "border border-md-outline focus-within:border-2 focus-within:border-md-primary"
    val labelCls = if hasError then "text-md-error" else "text-md-on-surface-variant"
    div(cls := s"relative mb-5 $borderCls rounded")(
      label(
        cls := s"absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide $labelCls",
        attribute("for", fieldID)
      )(text(labelName)),
      input(allAttributes*),
      maybeErrorMsg
        .map(errMsg => p(cls := "absolute -bottom-5 left-0 px-4 text-xs text-md-error")(text(errMsg)))
        .getOrElse(div()())
    )

  def select[M](
    fieldID:       String,
    labelName:     String,
    options:       Seq[(String, String)],
    selectedValue: String,
    onChangeFn:    String => M
  ): Html[M] =
    div(cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded")(
      label(
        cls := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant",
        attribute("for", fieldID)
      )(text(labelName)),
      tyrian.Html.select(
        id  := fieldID,
        cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onChange(onChangeFn)
      )(
        options.map { (value, label) =>
          option(
            tyrian.Html.value := value,
            if value == selectedValue then attribute("selected", "selected") else EmptyAttribute
          )(text(label))
        }*
      )
    )
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/eweise/projects/perfectpair && sbt "frontend/fastLinkJS" 2>&1 | tail -20
```

Expected: `[success]` with no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/components/Form.scala
git commit -m "feat: add Form.select component for enum/option dropdowns"
```

---

### Task 2: Use `Form.select` in `MaintainReconSpecView`

**Files:**
- Modify: `frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala`

- [ ] **Step 1: Replace the relationship `Form.field` with `Form.select`**

In `MaintainReconSpecView.scala`, replace:

```scala
        Form.field[AppMsg](
          fieldID      = "relationship",
          labelName    = "Relationship",
          defaultValue = Some(spec.relationship.toString),
          onChangFn = Some(value =>
            AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(relationship = ReconRelationship.valueOf(value))))
          )
        ),
```

With:

```scala
        Form.select[AppMsg](
          fieldID       = "relationship",
          labelName     = "Relationship",
          options       = ReconRelationship.values.toSeq.map(r => (r.toString, r.toString)),
          selectedValue = spec.relationship.toString,
          onChangeFn    = value => AppMsg.MaintainReconSpecMsg(Msg.FormUpdate(spec.copy(relationship = ReconRelationship.valueOf(value))))
        ),
```

- [ ] **Step 2: Verify it compiles**

```bash
cd /Users/eweise/projects/perfectpair && sbt "frontend/fastLinkJS" 2>&1 | tail -20
```

Expected: `[success]` with no errors.

- [ ] **Step 3: Verify in browser**

Start the dev server and navigate to a Recon Spec form (create or edit). Confirm:
- The Relationship field renders as a dropdown
- All four options appear: `OneToOne`, `OneToMany`, `ManyToOne`, `ManyToMany`
- The correct value is pre-selected when editing an existing spec
- Changing the selection and saving persists the chosen value

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main/scala/perfectpair/view/pages/MaintainReconSpecView.scala
git commit -m "feat: change Relationship field to select dropdown in MaintainReconSpecView"
```
