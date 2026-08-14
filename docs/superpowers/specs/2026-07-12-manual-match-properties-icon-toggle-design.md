# Manual Match Properties Toggle Icons — Design Spec

**Date:** 2026-07-12

## Overview

`ManualMatchPage`'s Reference ID cell toggle currently renders as plain `+`/`−`
characters. Replace them with solid circled plus/minus icons for better visual
prominence, following this codebase's existing inline-SVG icon convention
(`frontend/src/main/scala/onparity/view/components/Icons.scala`).

## Change

### `Icons.scala` (modified)

Add two new icons, following the existing `icon(svgStr: String): HtmlElement` helper
and Heroicons-solid style already used by the other icons in this file:

- `plusCircle` — solid circled plus (Heroicons `plus-circle`, 20x20 viewBox), class
  `"w-5 h-5 text-md-primary"`.
- `minusCircle` — solid circled minus (Heroicons `minus-circle`, 20x20 viewBox), same
  class.

### `ManualMatchPage.scala` (modified)

In `referenceIdCell`, replace the button's text content
(`child.text <-- state.signal.map(s => if s.expandedProperties.contains(m.id) then "−" else "+")`)
with the corresponding icon:

```scala
child <-- state.signal.map(s => if s.expandedProperties.contains(m.id) then Icons.minusCircle else Icons.plusCircle)
```

The button's `cls := "text-xs w-4 ..."` sizing constraint is removed since the icon
carries its own `w-5 h-5` sizing.

## Out of Scope

- Changing any other button/icon in the codebase.
- Animating the toggle (e.g. chevron rotation) — plain swap between two static icons.
