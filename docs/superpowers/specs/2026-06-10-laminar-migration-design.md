# Laminar Migration Design

**Date:** 2026-06-10
**Branch:** convert-to-laminar

## Overview

Migrate the PerfectPair frontend from Tyrian (Elm-inspired TEA architecture) to Laminar (FRP, Airstream-based). The migration is a full rewrite of the frontend Scala source, following an infrastructure-first order. The backend and `common` module are untouched.

## Stack Changes

| Remove | Add |
|--------|-----|
| `tyrian-zio` | `laminar 17.0.0` |
| `zio-interop-cats` | `frontroute 0.19.0` |
| `scalajs-java-securerandom` | — |
| ZIO `Task`, `Cmd[Task, Msg]` | Airstream `EventStream`, `Signal`, `Var` |

**Keep:** `upickle`, `quicklens`, `common.js`, Tailwind CSS, Flowbite CSS (Flowbite JS dropped).

## Architecture

### Paradigm Shift

Tyrian centralises all state in one `Model` and routes all events through one `Msg` enum dispatched by a global `update` function. Laminar inverts this: each component owns its own reactive state (`Var[State]`), and the app is wired together through signals and observers. There is no global dispatcher.

### Global State

Shared state that multiple pages need lives in two top-level objects:

```scala
// state/SessionStore.scala
object SessionStore:
  val current: Var[Option[User]] = Var(None)
  def restore(): Unit   // reads accessToken/refreshToken from localStorage on startup
  def save(user: User): Unit
  def clear(): Unit     // on logout

// state/AppState.scala
object AppState:
  val darkMode:        Var[Boolean]                 = Var(false)
  val globalError:     Var[Option[String]]          = Var(None)
  val fundFlowNavOpen: Var[Boolean]                 = Var(false)
  val fundFlows:       Var[Seq[FundFlowPayload]]    = Var(Seq.empty)
  val activeFundFlow:  Var[Option[FundFlowPayload]] = Var(None)
  val activeAccount:   Var[Option[AccountPayload]]  = Var(None)
```

`activeFundFlow` and `activeAccount` are set by navigation helpers before pushing a new URL. Pages that operate within a fund flow or account context read these directly rather than receiving them as parameters.

### Page-Local State

Each page declares a `case class State(...)` scoped to that component. A `Var[State]` is created inside the page's `apply()` method and is garbage-collected when the user navigates away. The `PageState` enum and the `Msg` enum from the Tyrian model are eliminated entirely.

## Routing

frontroute drives the main content area. Route definitions live in `routing/Routes.scala`:

```scala
object Routes:
  def apply(): Element =
    div(
      pathPrefix("components") {
        concat(
          path("fund-flows")              { FundFlowsPage() },
          path("fund-flow")               { FundFlowPage() },
          path("maintain-fund-flow")      { MaintainFundFlowPage() },
          path("accounts")               { AccountsPage() },
          path("maintain-account")       { MaintainAccountPage() },
          path("recon-specs")            { ReconSpecsPage() },
          path("maintain-recon-spec")    { MaintainReconSpecPage() },
          path("maintain-movement-spec") { MaintainMovementSpecPage() },
          path("ingest")                 { IngestPage() },
          path("jobs")                   { JobsPage() },
          path("unreconciled-movements") { UnreconciledMovementsPage() },
          path("reconciliations")        { ReconciliationsPage() }
        )
      },
      path("login") { LoginPage() },
      pathEnd        { WelcomePage() },
      redirectToPath("/login")
    )
```

Navigation is performed by calling `BrowserNavigation.pushState(url)` directly inside page event handlers. No `Msg.NavigateTo` or global dispatcher. When entering a fund flow or account context, the caller sets the relevant `AppState` var before pushing:

```scala
def navigateToAccounts(ff: FundFlowPayload): Unit =
  AppState.activeFundFlow.set(Some(ff))
  BrowserNavigation.pushState("/components/accounts")
```

## HTTP Client

`util/HttpHelper.scala` is replaced by `http/ApiClient.scala`, a thin wrapper over `dom.fetch` that returns `EventStream`:

```scala
object ApiClient:
  def get[A: Reader](path: String, token: String): EventStream[Either[String, A]]
  def post[A: Reader, B: Writer](path: String, body: B, token: String): EventStream[Either[String, A]]
  def put[A: Reader, B: Writer](path: String, body: B, token: String): EventStream[Either[String, A]]
  def delete(path: String, token: String): EventStream[Either[String, Unit]]
```

Each method wraps `dom.fetch(...).toFuture` in `EventStream.fromFuture`, decodes the response body with upickle, and returns `Right(value)` on success or `Left(errorMessage)` on failure.

Pages bind the stream with `-->` and route errors to `AppState.globalError`:

```scala
ApiClient.get[Seq[FundFlowPayload]](HttpPath.fundFlows, token) --> {
  case Right(ffs) => state.update(_.copy(fundFlows = ffs))
  case Left(err)  => AppState.globalError.set(Some(err))
}
```

The auth token is read synchronously from `SessionStore.current.now()` at the point the request fires. The `SendHttpRequestWithAccessToken` wrapper is eliminated.

upickle `ReadWriter` instances remain in `common` unchanged. The `ResponseDecoders` object is deleted; decoding happens inline at each call site.

## Component Patterns

### Page Components

```scala
object FundFlowsPage:
  case class State(fundFlows: Seq[FundFlowPayload] = Seq.empty)

  def apply(): Element =
    val state = Var(State())
    val token = SessionStore.current.now().map(_.accessToken).getOrElse("")

    div(
      ApiClient.get[Seq[FundFlowPayload]](HttpPath.fundFlows, token) --> {
        case Right(ffs) => state.update(_.copy(fundFlows = ffs))
        case Left(err)  => AppState.globalError.set(Some(err))
      },
      child <-- state.signal.map(renderTable)
    )
```

### Stateless Components

Shared helpers (Buttons, Form, Table, Icons) are plain functions taking `Observer[A]` for callbacks:

```scala
object Buttons:
  def link(label: String, onClick: Observer[Unit]): Element
  def text(label: String, onClick: Observer[Unit]): Element
```

### Stateful Sub-Components

RuleWidget and DataSourceConfigWidget own their own `Var[State]` and expose their result via a `Signal` so the parent can read the current value at save time:

```scala
object RuleWidget:
  def apply(initial: Rule): (Element, Signal[Rule]) =
    val state = Var(State.fromRule(initial))
    val element = div(...)
    (element, state.signal.map(State.toRule))
```

### Layout

`MainLayout` wraps all pages and reads directly from `AppState` signals:

```scala
object MainLayout:
  def apply(content: Element): Element =
    div(
      cls <-- AppState.darkMode.signal.map(d => if d then "dark bg-gray-900" else "..."),
      LeftNav(),
      child <-- AppState.globalError.signal.map(_.fold(emptyNode)(errorBanner)),
      main(cls := "flex flex-col min-h-screen ml-64 ...")(content)
    )
```

## File Structure

```
frontend/src/main/scala/perfectpair/
  main/
    WebApp.scala
  state/
    SessionStore.scala
    AppState.scala
  routing/
    Routes.scala
  http/
    ApiClient.scala
  view/
    layout/
      MainLayout.scala
      LeftNav.scala
    components/
      Buttons.scala
      Form.scala
      Table.scala
      Icons.scala
      RuleWidget.scala
      DataSourceConfigWidget.scala
    pages/
      LoginPage.scala
      WelcomePage.scala
      FundFlowsPage.scala
      FundFlowPage.scala
      MaintainFundFlowPage.scala
      AccountsPage.scala
      MaintainAccountPage.scala
      MaintainMovementSpecPage.scala
      ReconSpecsPage.scala
      MaintainReconSpecPage.scala
      IngestPage.scala
      JobsPage.scala
      UnreconciledMovementsPage.scala
      ReconciliationsPage.scala
```

## Files Deleted

| File | Reason |
|------|--------|
| `route/route.scala` | Replaced by frontroute |
| `page/Page.scala` | Replaced by URL strings |
| `model/model.scala` | Global Msg/Model/PageState eliminated |
| `util/Authentication.scala` | Logic absorbed into LoginPage + SessionStore |
| `util/Flowbite.scala` | Flowbite JS dropped |
| `util/PayloadAction.scala` | Action logic inlined into pages |
| `util/PrettyLogger.scala` | Replaced with `console.log` / `println` |
| `util/LocalStorageHelper.scala` | Logic moved into SessionStore |

## Files Kept and Ported

- `util/ViewHelper.scala` — date formatting, no framework dependency
- `util/ElementIdGenerator.scala` — no framework dependency
- All `common` module files — untouched

## Entry Point

`WebApp.scala` becomes a plain Scala.js `@main` method:

```scala
@JSExportTopLevel("WebApp")
object WebApp:
  def main(args: Array[String]): Unit =
    SessionStore.restore()
    renderOnDomContentLoaded(
      dom.document.getElementById("app"),
      MainLayout(Routes())
    )
```

The `@JSExportTopLevel("TyrianApp")` annotation and `TyrianZIOApp` base class are removed. `main.js` is updated to call `WebApp.main()` instead of `TyrianApp`.

## Implementation Order (Infrastructure-First)

1. **Build config** — swap dependencies in `build.sbt`
2. **State layer** — `SessionStore`, `AppState`
3. **HTTP client** — `ApiClient`
4. **Routing shell** — `Routes`, `WebApp`, `MainLayout`, `LeftNav` (stub pages)
5. **Shared components** — `Buttons`, `Form`, `Table`, `Icons`
6. **Stateful components** — `RuleWidget`, `DataSourceConfigWidget`
7. **Pages** — in dependency order: Login → Welcome → FundFlows → FundFlow → MaintainFundFlow → Accounts → MaintainAccount → MaintainMovementSpec → ReconSpecs → MaintainReconSpec → Ingest → Jobs → UnreconciledMovements → Reconciliations
