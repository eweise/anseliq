# Fund Flow Nav Submenu — Design Spec

**Date:** 2026-04-30

## Overview

Add an accordion submenu under the "Fund Flows" left nav item that lists individual fund flow names. Clicking a fund flow name navigates to a new `FundFlowView` detail page. Fund flows are fetched at session start so the submenu is always populated.

## Goals

- Fund flow names always appear in the sidebar submenu without requiring the user to visit the Fund Flows list page first.
- The submenu is collapsible (accordion) — expanded/collapsed by clicking the "Fund Flows" header item.
- Clicking a fund flow name navigates to a new `FundFlowView` page scoped to that fund flow.
- The `FundFlowView` page shows the fund flow name for now; its content will be extended later.

## Architecture

### 1. Fetch Fund Flows on Auth

**File:** `frontend/src/main/scala/onparity/main/WebApp.scala`

The `Msg.RestoredSession` handler (the single auth-success path for both fresh login and localStorage session restore) emits `HttpHelper.fetchFundFlows` in addition to its existing `DoNavigate(Page.Home)` command. The result is handled by the existing `FundFlowsMsg(FundFlowsView.Msg.FundFlowsFromBackend(...))` path and stored in `model.fundFlowsModel.fundFlows`.

No new storage field is needed — the existing `fundFlowsModel` holds the list.

### 2. Accordion Toggle State

**File:** `frontend/src/main/scala/onparity/model/model.scala`

Add `fundFlowNavOpen: Boolean = false` to the `Model` case class.

Add `Msg.ToggleFundFlowNav` to the `Msg` enum.

Handle `Msg.ToggleFundFlowNav` in `WebApp.update` to flip `model.fundFlowNavOpen`.

### 3. Sidebar Changes

**File:** `frontend/src/main/scala/onparity/view/MainContainer.scala`

- `leftNav()` gains two parameters: `fundFlows: Seq[FundFlowPayload]` and `fundFlowNavOpen: Boolean`.
- `MainContainer.apply()` gains the same two parameters and passes them to `leftNav()`.
- `WebApp.view` passes `model.fundFlowsModel.fundFlows` and `model.fundFlowNavOpen` into `MainContainer`.

The "Fund Flows" nav item is rendered as a row with two interaction zones:
- The label/icon area remains a link that navigates to `Page.FundFlows` (existing behaviour preserved).
- A chevron icon on the right emits `Msg.ToggleFundFlowNav` to open or close the accordion.

When `fundFlowNavOpen` is true, an indented list of fund flow names is rendered below the header row. Each name is a nav item that emits `Msg.NavigateToFundFlow(ff)`.

### 4. New FundFlowView Page

**New file:** `frontend/src/main/scala/onparity/view/pages/FundFlowView.scala`

A minimal view object following the same pattern as `ReconciliationsView`:

```
object FundFlowView:
  case class Model(fundFlow: FundFlowPayload = FundFlowPayload())
  // no messages needed initially
  def apply(model: Model): Html[AppMsg] = ... // shows fundFlow.name
```

**File:** `frontend/src/main/scala/onparity/model/model.scala`

Add `fundFlowViewModel: FundFlowView.Model = FundFlowView.Model()` to `Model`.

Add `Msg.NavigateToFundFlow(ff: FundFlowPayload)` to `Msg`.

**File:** `frontend/src/main/scala/onparity/page/Page.scala`

Add `object FundFlow extends Page` with:
- `render`: delegates to `FundFlowView(m.fundFlowViewModel)`
- `update`: no-op initially
- `doNavigate`: no API call needed (data is carried in the model)

**File:** `frontend/src/main/scala/onparity/route/route.scala`

Add a route for `/components/fund-flow`.

**File:** `frontend/src/main/scala/onparity/main/WebApp.scala`

Handle `Msg.NavigateToFundFlow(ff)`:
```
model.copy(fundFlowViewModel = FundFlowView.Model(ff)),
Cmd.Emit(Msg.NavigateTo(Page.FundFlow))
```

## Data Flow

```
App init / login
  └─ Msg.RestoredSession
       └─ HttpHelper.fetchFundFlows
            └─ Msg.FundFlowsMsg(FundFlowsFromBackend(ffs))
                 └─ model.fundFlowsModel.fundFlows = ffs

User clicks "Fund Flows" in sidebar
  └─ Msg.ToggleFundFlowNav
       └─ model.fundFlowNavOpen flips

User clicks a fund flow name in sidebar
  └─ Msg.NavigateToFundFlow(ff)
       └─ model.fundFlowViewModel = FundFlowView.Model(ff)
       └─ Msg.NavigateTo(Page.FundFlow)
            └─ FundFlowView renders ff.name
```

## Out of Scope

- Refreshing the fund flows list when a fund flow is created or renamed (acceptable for now; user can reload).
- Pagination or search within the submenu (acceptable for now).
- Content beyond the fund flow name on `FundFlowView` (intentionally deferred).
