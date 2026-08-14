# Page-Scoped Model and Message Architecture

**Date:** 2026-04-10
**Status:** Approved

## Overview

Refactor the frontend from a single flat `Msg` enum and monolithic `Model` to a per-page architecture where each page owns its `Msg`, `Model`, `update`, and `view`. The parent `Model` holds references to page models; the top-level `Msg` wraps page-specific messages. The `Page` enum delegates updates to each page's companion object.

## Goals

- Each page is a self-contained module: `Msg`, `Model`, `update`, `view` in one file
- Top-level `Msg` and `Model` shrink to globals + wrappers
- `WebApp.update` handles only global messages; delegates page messages to `model.currentPage.update`
- No new files — existing view files grow to include the page's logic

## Architecture

### Pattern: Companion Objects per Page

Each page view file contains a single object with four members. Existing object names are kept as-is (`LoginPage`, `FundFlowsView`, `MaintainFundFlowView`, `AccountsView`, `Welcome`, `ToggleView`):

```scala
object FundFlowsView:
  enum Msg:
    case FundFlowsFromBackend(fundFlows: Seq[FundFlowPayload])
    case SelectFundFlow(fundFlow: FundFlowPayload)
    case GetAccountsForFundFlow(fundFlow: FundFlowPayload)

  case class Model(fundFlows: Seq[FundFlowPayload] = Seq())

  def update(model: Model, msg: Msg): (Model, Cmd[Task, onparity.model.Msg]) = ...

  def view(model: Model): Html[onparity.model.Msg] = ...
```

Pages reference the top-level `Msg` directly for navigation and global actions (e.g. `Msg.NavigateTo(Page.Home)`). Page-internal state changes travel as wrapped variants (e.g. `Msg.FundFlowsMsg(FundFlowsView.Msg.SelectFundFlow(ff))`).

### Message Routing and Dropped Messages

`WebApp.update` delegates unrecognised messages to `model.currentPage.update`. Each page's `update` returns `(model, Cmd.None)` for messages it doesn't own. This means a message for a non-current page (e.g. an HTTP response that arrives after the user has navigated away) is silently dropped. This is intentional Elm-style behaviour — in-flight side effects are abandoned on navigation. It is acceptable for this codebase.

### Cross-cutting Concern: Login Success

`LoginPage.update` handles `LoginPage.Msg.LoginSuccess` by:
1. Updating `LoginPage.Model` (clear form, reset loading)
2. Emitting `Cmd[Task, TopMsg]`: `LocalStorageHelper.storeSession(at, rt) |+| Cmd.Emit(Msg.RestoredSession(at, rt))`

The top-level `Msg.RestoredSession` handler (in `WebApp.update`) updates `model.user` and navigates to Home. This keeps authenticated user state at the global level where it belongs.

## Top-level `Msg` Changes

**Stays in top-level `Msg` (global):**
```
NoOp, LogMessage, NavigateTo, DoNavigate, UnhandledRoute,
GoToInternet, ToggleDarkMode, Error, Logout,
RestoredSession, SendHttpRequestWithAccessToken
```

**Added wrappers (one per page):**
```scala
case LoginMsg(msg: LoginPage.Msg)
case HomeMsg(msg: Welcome.Msg)
case FundFlowsMsg(msg: FundFlowsView.Msg)
case MaintainFundFlowMsg(msg: MaintainFundFlowView.Msg)
case AccountsMsg(msg: AccountsView.Msg)
```

**Removed from top-level `Msg`** (moved into page objects):
- `SubmitLogin`, `UpdateLoginForm`, `LoginSuccess`, `LoginFailure` → `LoginPage.Msg`
- `NextBackendMessage`, `GreetingFromBackend` → `Welcome.Msg`
- `FundFlowsFromBackend`, `SelectFundFlow`, `GetAccountsForFundFlow` → `FundFlowsView.Msg`
- `FundFlowFormUpdate`, `CreateFundFlow`, `FundFlowCreated` → `MaintainFundFlowView.Msg`
- `GetReconAccounts`, `ReconAccountsFromBackend` → `AccountsView.Msg`

## Top-level `Model` Changes

```scala
case class Model(
  currentPage:           Page,
  user:                  Option[User],
  isDarkMode:            Boolean,
  loginModel:            LoginPage.Model,
  homeModel:             Welcome.Model,
  fundFlowsModel:        FundFlowsView.Model,
  maintainFundFlowModel: MaintainFundFlowView.Model,
  accountsModel:         AccountsView.Model,
)
```

Removed from top-level `Model`: `loginForm`, `homeState`, `fundFlows`, `selectedFundFlow`, `accounts` — all moved into their respective page models.

`Model.init` initialises each page model via its companion (e.g. `FundFlowsPage.Model()`).

## `Page` Enum Changes

The `Page` enum gains an abstract `update` method. Each case delegates to its page object:

```scala
enum Page(
  val path: String,
  val render: Model => Html[Msg],
  beforeEnter: Model => Cmd[Task, Msg] = _ => Cmd.None,
  val isSecured: Boolean = false
):
  def doNavigate(model: Model): Cmd[Task, Msg] = ...  // unchanged
  def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg])  // abstract

  case Login extends Page("/login", m => LoginPage.view(m.loginModel)):
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.LoginMsg(sub) =>
        val (sub2, cmd) = LoginPage.update(model.loginModel, sub)
        (model.copy(loginModel = sub2), cmd)
      case _ => (model, Cmd.None)

  case FundFlows extends Page("/components/fund-flows", m => FundFlowsView.view(m.fundFlowsModel), ...):
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.FundFlowsMsg(sub) =>
        val (sub2, cmd) = FundFlowsView.update(model.fundFlowsModel, sub)
        (model.copy(fundFlowsModel = sub2), cmd)
      case _ => (model, Cmd.None)

  // Home, MaintainFundFlow, Accounts, Toggle follow the same pattern
```

## `WebApp.update` Changes

Global messages are handled directly; everything else is delegated:

```scala
def update(model: Model): Msg => (Model, Cmd[Task, Msg]) =
  case Msg.NavigateTo(page)                         => ...  // unchanged
  case Msg.DoNavigate(page)                         => ...  // unchanged
  case Msg.Logout                                   => ...  // unchanged
  case Msg.ToggleDarkMode                           => ...  // unchanged
  case Msg.RestoredSession(at, rt)                  => ...  // unchanged
  case Msg.Error(msg)                               => ...  // unchanged
  case Msg.LogMessage(msg)                          => ...  // unchanged
  case Msg.UnhandledRoute(path)                     => ...  // unchanged
  case Msg.GoToInternet(loc)                        => ...  // unchanged
  case Msg.NoOp                                     => ...  // unchanged
  case Msg.SendHttpRequestWithAccessToken(f)        => ...  // unchanged
  case msg => model.currentPage.update(model, msg)          // delegate to page
```

## File Structure

No new files. Each existing view file expands to include `Msg`, `Model`, and `update`:

```
frontend/src/main/scala/onparity/
  model/
    model.scala                    ← Msg (globals + wrappers), Model (page fields + user)
  page/
    Page.scala                     ← Page enum with abstract update; render delegates to page objects
  view/pages/
    Login.scala                    ← object LoginPage { Msg; Model; update; view }
    Welcome.scala                  ← object Welcome { Msg; Model; update; view }
    FundFlowsView.scala            ← object FundFlowsView { Msg; Model; update; view }
    MaintainFundFlowView.scala     ← object MaintainFundFlowView { Msg; Model; update; view }
    AccountsView.scala             ← object AccountsView { Msg; Model; update; view }
    ToggleView.scala               ← object ToggleView { Msg (empty); Model; update (noop); view }
  main/
    WebApp.scala                   ← global update cases + delegate to currentPage.update
```

## Implementation Order

1. Add page `Msg` and `Model` to each view file (no behaviour change yet)
2. Add `update` to each page object, migrating logic from `WebApp.update`
3. Update top-level `Model` to hold page models; update `Model.init`
4. Update top-level `Msg` — add wrappers, remove moved variants
5. Add abstract `update` to `Page` enum; implement per case; update `render` to pass page model slice
6. Slim down `WebApp.update` to globals + delegate
7. Compile and fix remaining references (view sites that emit old Msg variants)
