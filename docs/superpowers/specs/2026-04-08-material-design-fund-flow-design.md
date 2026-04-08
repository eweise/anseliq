# Material Design 3 — MaintainFundFlow Page

**Date:** 2026-04-08

## Goal

Apply Material Design 3 (M3) styling to the MaintainFundFlow page and update the shared `Form` and `Buttons` components to use it.

## Decisions Made

| Decision | Choice | Reason |
|----------|--------|--------|
| Visual style | Elevated card (Option C) | Groups the form as a visual unit; cleaner than flat layout |
| Scope | Update shared components | Consistent style across all pages that use Form/Buttons |
| Color palette | M3 purple (`#6750a4`) | Standard M3 default; no custom brand color required |
| Implementation | Tailwind theme tokens | One place to change palette; supports future dark mode (config already has `darkMode: 'selector'`) |

## Color Tokens

Add to `tailwind.config.js` under `theme.extend.colors`:

| Token | Value | Usage |
|-------|-------|-------|
| `md-primary` | `#6750a4` | Focused border, label, filled button background |
| `md-on-primary` | `#ffffff` | Text on filled button |
| `md-primary-container` | `#eaddff` | Hover/ripple surfaces |
| `md-surface-variant` | `#ece6f0` | Page background behind the card |
| `md-on-surface` | `#1c1b1f` | Input text, card title |
| `md-on-surface-variant` | `#49454f` | Default label text, supporting text |
| `md-outline` | `#79747e` | Default input border |
| `md-error` | `#b3261e` | Error border, error label, error helper text |

## Component Changes

### `Form.scala` — `Form.field`

Replace existing class strings with M3 outlined field pattern:

- **Container:** `relative mb-5`
- **Label:** Positioned above the border (`absolute -top-2.5 left-3`), `bg-white px-1 text-xs font-medium tracking-wide`. Default color: `text-md-on-surface-variant`. Error color: `text-md-error`.
- **Input:** `border border-md-outline rounded text-md-on-surface text-base w-full px-4 py-3.5 bg-white focus:outline-none focus:border-2 focus:border-md-primary`
- **Error state:** Border becomes `border-md-error border-2`; helper text below input in `text-md-error text-xs`

### `Buttons.scala`

Add a `textButton` variant alongside the existing `linkButton`:

- **`linkButton` (filled):** `bg-md-primary text-md-on-primary rounded-full px-6 py-2.5 text-sm font-medium tracking-wide hover:bg-purple-800 shadow-sm`
- **`textButton` (text/cancel):** `text-md-primary rounded-full px-6 py-2.5 text-sm font-medium tracking-wide hover:bg-md-primary-container`

### `MaintainFundFlowView.scala`

Wrap the form in an elevated card on a tinted surface:

- **Page wrapper:** `min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4`
- **Card:** `bg-white rounded-xl shadow-md w-full max-w-md p-8`
- **Card title:** `text-xl font-medium text-md-on-surface mb-7`
- **Actions row:** `flex justify-end gap-2 mt-6` — `textButton("Cancel", ...)` + `linkButton("Save", ...)`

## Files Changed

1. `frontend/tailwind.config.js`
2. `frontend/src/main/scala/perfectpair/view/components/Form.scala`
3. `frontend/src/main/scala/perfectpair/view/components/Buttons.scala`
4. `frontend/src/main/scala/perfectpair/view/pages/MaintainFundFlowView.scala`

## Out of Scope

- Dark mode implementation (config supports it; left for a future task)
- Other pages — shared component changes will affect them visually, but no page-specific layout changes outside MaintainFundFlow
