# Page-Scoped Model and Message Architecture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the frontend so each page owns its `Msg`, `Model`, `update`, and `view`; the top-level `Msg` wraps page messages; the top-level `Model` holds page model fields; `Page` enum delegates `update` to each page object.

**Architecture:** Companion-style objects per page contain all four members. Top-level `Msg` keeps globals and adds one wrapper variant per page. `WebApp.update` handles global messages and delegates everything else to `model.currentPage.update(model, msg)`. Navigation with context (selecting a fund flow for editing or accounts view) uses dedicated global messages (`NavigateToMaintainFundFlow`, `NavigateToAccounts`) handled in `WebApp.update`.

**Tech Stack:** Scala 3, Scala.js, Tyrian (Elm-style UI framework), ZIO, sbt

---

## File Map

| File | Change |
|------|--------|
| `frontend/src/main/scala/onparity/view/pages/Login.scala` | Add `Msg`, `Model`, `update`; refactor `apply` to use `Model` |
| `frontend/src/main/scala/onparity/view/pages/Welcome.scala` | Add `Msg`, `Model`, `update`; refactor `apply` to use `Model` |
| `frontend/src/main/scala/onparity/view/pages/FundFlowsView.scala` | Add `Msg`, `Model`, `update`; refactor `apply` to use `Model` |
| `frontend/src/main/scala/onparity/view/pages/MaintainFundFlowView.scala` | Add `Msg`, `Model`, `update`; refactor `apply` to use `Model` |
| `frontend/src/main/scala/onparity/view/pages/AccountsView.scala` | Add `Msg`, `Model`, `update`; refactor `apply` to use `Model` |
| `frontend/src/main/scala/onparity/view/pages/ToggleView.scala` | Add empty `Msg`, unit `Model`, noop `update` |
| `frontend/src/main/scala/onparity/model/model.scala` | Add page Msg wrappers + 2 new global msgs; replace old fields with page model fields; remove moved Msg variants |
| `frontend/src/main/scala/onparity/page/Page.scala` | Add abstract `update`; implement per case; update `render` to pass page model slice |
| `frontend/src/main/scala/onparity/main/WebApp.scala` | Keep global handlers; add 2 new global msg handlers; add catch-all delegate |
| `frontend/src/main/scala/onparity/util/HttpHelper.scala` | Update all decoders to emit wrapped page messages; simplify type signatures |

---

## Phase 1 — Add page `Msg`, `Model`, `update` to view files (additive only)

These tasks add new code alongside existing code. The existing `apply()` methods are unchanged. **Do not run `sbt compile` after individual Phase 1 tasks** — Task 3's `update` references `AppMsg.NavigateToMaintainFundFlow` which is only added in Task 7. The first compile gate is after all of Phase 2 (Task 17).

---

### Task 1: Add `Msg`, `Model`, `update` to `LoginPage`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/Login.scala`

- [ ] **Step 1: Add imports and page members to `object LoginPage`**

Add the following before the existing `private def formField` method. The existing `apply` method is unchanged for now.

```scala
package onparity.view.pages

import tyrian.Html.*
import tyrian.Html.{attribute => attr}
import tyrian.*
import onparity.model.Model.LoginForm
import onparity.model.{Msg as AppMsg}
import onparity.util.{ElementIdGenerator, HttpHelper, LocalStorageHelper}
import onparity.view.components.Icons
import zio.Task
import zio.interop.catz.*

object LoginPage:

  enum Msg:
    case UpdateUsername(value: String)
    case UpdatePassword(value: String)
    case Submit
    case LoginSuccess(accessToken: String, refreshToken: String)
    case LoginFailure(message: String)

  case class Model(
    username:  String,
    password:  String,
    error:     Option[String],
    isLoading: Boolean
  ):
    def isSubmitDisabled: Boolean = username.isBlank() || password.isBlank()

  object Model:
    val init: Model = Model("Scala", "nopassword", None, false)

  def update(model: Model, msg: Msg): (Model, tyrian.Cmd[Task, AppMsg]) =
    msg match
      case Msg.UpdateUsername(v) =>
        (model.copy(username = v), tyrian.Cmd.None)
      case Msg.UpdatePassword(v) =>
        (model.copy(password = v), tyrian.Cmd.None)
      case Msg.Submit =>
        (model.copy(isLoading = true), HttpHelper.login(model.username, model.password))
      case Msg.LoginSuccess(at, rt) =>
        (Model.init, LocalStorageHelper.storeSession(at, rt) |+| tyrian.Cmd.Emit(AppMsg.RestoredSession(at, rt)))
      case Msg.LoginFailure(message) =>
        (model.copy(error = Some(message), isLoading = false), tyrian.Cmd.None)

  // ---- existing code below (unchanged for now) ----

  private def formField( ...
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/Login.scala
git commit -m "feat: add Msg, Model, update to LoginPage"
```

---

### Task 2: Add `Msg`, `Model`, `update` to `Welcome`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/Welcome.scala`

- [ ] **Step 1: Add members to `object Welcome`**

Insert before the existing `def apply`:

```scala
package onparity.view.pages

import tyrian.Html.*
import tyrian.Html
import tyrian.Cmd
import onparity.model.Model.HomeState
import onparity.model.{Msg as AppMsg}
import onparity.util.{HttpHelper, PrettyLogger}
import onparity.view.components.Icons
import zio.Task
import zio.interop.catz.*

object Welcome:

  enum Msg:
    case NextBackendMessage
    case GreetingFromBackend(text: String)

  case class Model(serverMessage: Option[String] = None)

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.NextBackendMessage =>
        (model, HttpHelper.fetchServerMessage2 |+| PrettyLogger.info("fetchserverMessage2"))
      case Msg.GreetingFromBackend(text) =>
        (model.copy(serverMessage = Some(text)), PrettyLogger.info(text))

  // ---- existing apply below (unchanged for now) ----

  def apply(state: HomeState): Html[AppMsg] = ...
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/Welcome.scala
git commit -m "feat: add Msg, Model, update to Welcome"
```

---

### Task 3: Add `Msg`, `Model`, `update` to `FundFlowsView`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/FundFlowsView.scala`

- [ ] **Step 1: Add members to `object FundFlowsView`**

Insert before the existing `def apply`:

```scala
package onparity.view.pages

import onparity.common.model.FundFlowPayload
import onparity.model.{Msg as AppMsg}
import onparity.page.Page
import onparity.util.{HttpHelper, PrettyLogger}
import onparity.view.components.{Buttons, Table}
import tyrian.{Cmd, Html}
import tyrian.Html.*
import zio.Task
import zio.interop.catz.*

import java.time.format.DateTimeFormatter

object FundFlowsView:

  enum Msg:
    case FundFlowsFromBackend(fundFlows: Seq[FundFlowPayload])
    case SelectFundFlow(fundFlow: FundFlowPayload)
    case GetAccountsForFundFlow(fundFlow: FundFlowPayload)

  case class Model(fundFlows: Seq[FundFlowPayload] = Seq())

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FundFlowsFromBackend(ff) =>
        (model.copy(fundFlows = ff), PrettyLogger.info("updated model with fund flows"))
      case Msg.SelectFundFlow(ff) =>
        (model, Cmd.Emit(AppMsg.NavigateToMaintainFundFlow(ff)))
      case Msg.GetAccountsForFundFlow(ff) =>
        (model, Cmd.Emit(AppMsg.NavigateToAccounts(ff)))

  // ---- existing apply below (unchanged for now) ----

  def apply(fundFlows: Seq[FundFlowPayload]): Html[AppMsg] = ...
```

Note: `AppMsg.NavigateToMaintainFundFlow` and `AppMsg.NavigateToAccounts` will be added to the top-level `Msg` in Task 7. The compiler will flag them as missing — leave this for now; Phase 2 fixes it.

- [ ] **Step 2: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/FundFlowsView.scala
git commit -m "feat: add Msg, Model, update to FundFlowsView"
```

---

### Task 4: Add `Msg`, `Model`, `update` to `MaintainFundFlowView`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/MaintainFundFlowView.scala`

- [ ] **Step 1: Add members to `object MaintainFundFlowView`**

Insert before the existing `def apply`:

```scala
package onparity.view.pages

import onparity.common.model.FundFlowPayload
import onparity.model.{Msg as AppMsg}
import onparity.page.Page
import onparity.util.{HttpHelper, PrettyLogger}
import onparity.view.components.{Buttons, Form}
import tyrian.{Cmd, Html}
import tyrian.Html.*
import zio.Task
import zio.interop.catz.*

object MaintainFundFlowView:

  enum Msg:
    case FundFlowFormUpdate(fundFlow: FundFlowPayload)
    case CreateFundFlow(fundFlow: FundFlowPayload)
    case FundFlowCreated(fundFlow: FundFlowPayload)

  case class Model(fundFlow: FundFlowPayload = FundFlowPayload())

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FundFlowFormUpdate(ff) =>
        (model.copy(fundFlow = ff), PrettyLogger.info(s"fund flow form modified: $ff"))
      case Msg.CreateFundFlow(ff) =>
        val validationErrs = ff.validate
        if validationErrs.nonEmpty then (model, Cmd.emit(AppMsg.Error(validationErrs.toString)))
        else (model, HttpHelper.createFundFlow(ff) |+| PrettyLogger.info("creating fund flow"))
      case Msg.FundFlowCreated(ff) =>
        (model.copy(fundFlow = ff), PrettyLogger.info("created fund flow"))

  // ---- existing apply below (unchanged for now) ----

  def apply(ff: FundFlowPayload): Html[AppMsg] = ...
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/MaintainFundFlowView.scala
git commit -m "feat: add Msg, Model, update to MaintainFundFlowView"
```

---

### Task 5: Add `Msg`, `Model`, `update` to `AccountsView`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/AccountsView.scala`

- [ ] **Step 1: Add members to `object AccountsView`**

Insert before the existing `def apply`:

```scala
package onparity.view.pages

import onparity.common.model.{AccountPayload, FundFlowPayload}
import onparity.model.{Msg as AppMsg}
import onparity.page.Page
import onparity.util.{HttpHelper, PrettyLogger}
import onparity.view.components.{Icons, Table}
import tyrian.{Cmd, Html}
import tyrian.Html.*
import zio.Task
import zio.interop.catz.*

object AccountsView:

  enum Msg:
    case GetReconAccounts
    case ReconAccountsFromBackend(accounts: Seq[AccountPayload])

  case class Model(
    fundFlow: FundFlowPayload       = FundFlowPayload(),
    accounts: Seq[AccountPayload]   = Seq()
  )

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.GetReconAccounts =>
        (model, HttpHelper.fetchReconAccounts |+| PrettyLogger.info("fetch recon accounts"))
      case Msg.ReconAccountsFromBackend(accounts) =>
        (model.copy(accounts = accounts), PrettyLogger.info("updated model with reconAccounts"))

  // ---- existing apply below (unchanged for now) ----

  def apply(fundFlowPayload: FundFlowPayload, accounts: Seq[AccountPayload]): Html[AppMsg] = ...
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/AccountsView.scala
git commit -m "feat: add Msg, Model, update to AccountsView"
```

---

### Task 6: Add `Msg`, `Model`, `update` to `ToggleView`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/ToggleView.scala`

- [ ] **Step 1: Add minimal members to `object ToggleView`**

Insert at the top of `object ToggleView`, before `def apply`:

```scala
  // ToggleView has no page-specific state or messages
  enum Msg

  case class Model()

  def update(model: Model, msg: Msg): (Model, tyrian.Cmd[zio.Task, onparity.model.Msg]) =
    (model, tyrian.Cmd.None)
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/main/scala/onparity/view/pages/ToggleView.scala
git commit -m "feat: add empty Msg, Model, update to ToggleView"
```

---

## Phase 2 — Rewrite `model.scala`, `Page.scala`, `WebApp.scala`, `HttpHelper.scala`

These tasks change the core types. Make all changes in Tasks 7–10 before compiling.

---

### Task 7: Rewrite `model.scala`

**Files:**
- Modify: `frontend/src/main/scala/onparity/model/model.scala`

- [ ] **Step 1: Replace the entire file with the new version**

```scala
package onparity.model

import onparity.common.model.FundFlowPayload
import onparity.common.util.JwtHelper
import onparity.model.Model.*
import onparity.page.Page
import onparity.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainFundFlowView, Welcome}
import tyrian.{Cmd, Location}
import zio.Task

enum Msg:
  // Global messages
  case NoOp
  case LogMessage(msg: String)
  case NavigateTo(page: Page)
  case DoNavigate(page: Page)
  case UnhandledRoute(path: String)
  case GoToInternet(loc: Location.External)
  case ToggleDarkMode
  case Error(msg: String)
  case SendHttpRequestWithAccessToken(sendWithAccessToken: String => Cmd[Task, Msg])
  case RestoredSession(accessToken: String, refreshToken: String)
  case Logout

  // Navigation with context (carry page state to destination)
  case NavigateToMaintainFundFlow(ff: FundFlowPayload)
  case NavigateToAccounts(ff: FundFlowPayload)

  // Page message wrappers
  case LoginMsg(msg: LoginPage.Msg)
  case HomeMsg(msg: Welcome.Msg)
  case FundFlowsMsg(msg: FundFlowsView.Msg)
  case MaintainFundFlowMsg(msg: MaintainFundFlowView.Msg)
  case AccountsMsg(msg: AccountsView.Msg)

case class Model(
  currentPage:           Page,
  user:                  Option[User],
  isDarkMode:            Boolean,
  loginModel:            LoginPage.Model,
  homeModel:             Welcome.Model,
  fundFlowsModel:        FundFlowsView.Model,
  maintainFundFlowModel: MaintainFundFlowView.Model,
  accountsModel:         AccountsView.Model,
):
  def toggleDarkMode: Model = copy(isDarkMode = !isDarkMode)

  def isLoggedIn: Boolean = user.isDefined

  def navigateTo(page: Page): Model = copy(currentPage = page)

  def loginSuccess(accessToken: String, refreshToken: String): Model =
    JwtHelper.getUnverifiedUserClaim(accessToken).map(claim => User(claim.username, accessToken, refreshToken)) match
      case Right(u) => this.copy(user = Some(u))
      case Left(_)  => this

  def logout: Model = copy(user = None)

object Model:
  val init: Model = Model(
    currentPage           = Page.Login,
    user                  = None,
    isDarkMode            = false,
    loginModel            = LoginPage.Model.init,
    homeModel             = Welcome.Model(),
    fundFlowsModel        = FundFlowsView.Model(),
    maintainFundFlowModel = MaintainFundFlowView.Model(),
    accountsModel         = AccountsView.Model()
  )

  case class User(username: String, accessToken: String, refreshToken: String)
```

Note: `LoginForm`, `MaintainFundFlowForm`, `HomeState` are removed. `emptyLoginForm`, `emptyHomeState` are removed.

---

### Task 8: Rewrite `Page.scala`

**Files:**
- Modify: `frontend/src/main/scala/onparity/page/Page.scala`

- [ ] **Step 1: Replace the entire file with the new version**

```scala
package onparity.page

import tyrian.Cmd
import tyrian.Html
import zio.*
import zio.interop.catz.*
import onparity.model.*
import onparity.view.pages.*
import onparity.util.{HttpHelper, PrettyLogger}

enum Page(
  val path: String,
  val render: Model => Html[Msg],
  beforeEnter: Model => Cmd[Task, Msg] = _ => Cmd.None,
  val isSecured: Boolean = false
):
  def doNavigate(model: Model): Cmd[Task, Msg] =
    beforeEnter(model) |+| PrettyLogger.info("Navigating To Page") |+| Cmd.emit(Msg.DoNavigate(this))

  def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg])

  case Login extends Page("/login", m => LoginPage(m.loginModel)):
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.LoginMsg(sub) =>
        val (sub2, cmd) = LoginPage.update(model.loginModel, sub)
        (model.copy(loginModel = sub2), cmd)
      case _ => (model, Cmd.None)

  case Home extends Page("/", m => Welcome(m.homeModel), _ => HttpHelper.fetchServerMessage):
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.HomeMsg(sub) =>
        val (sub2, cmd) = Welcome.update(model.homeModel, sub)
        (model.copy(homeModel = sub2), cmd)
      case _ => (model, Cmd.None)

  case FundFlows extends Page(
    "/components/fund-flows",
    m => FundFlowsView(m.fundFlowsModel),
    _ => PrettyLogger.info("Entering Fund Flows page") |+| HttpHelper.fetchFundFlows
  ):
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.FundFlowsMsg(sub) =>
        val (sub2, cmd) = FundFlowsView.update(model.fundFlowsModel, sub)
        (model.copy(fundFlowsModel = sub2), cmd)
      case _ => (model, Cmd.None)

  case MaintainFundFlow extends Page(
    "/components/maintain-fund-flow",
    m => MaintainFundFlowView(m.maintainFundFlowModel),
    _ => PrettyLogger.info("Entering Maintain Fund Flow page")
  ):
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainFundFlowMsg(sub) =>
        val (sub2, cmd) = MaintainFundFlowView.update(model.maintainFundFlowModel, sub)
        (model.copy(maintainFundFlowModel = sub2), cmd)
      case _ => (model, Cmd.None)

  case Accounts extends Page(
    "/components/accounts",
    m => AccountsView(m.accountsModel),
    _ => PrettyLogger.info("Entering Accounts page")
  ):
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.AccountsMsg(sub) =>
        val (sub2, cmd) = AccountsView.update(model.accountsModel, sub)
        (model.copy(accountsModel = sub2), cmd)
      case _ => (model, Cmd.None)

  case Toggle extends Page("/components/toggle", _ => ToggleView(), _ => PrettyLogger.info("Entering Toggle page")):
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = (model, Cmd.None)
```

Note: `Page.Login` `render` now calls `LoginPage(m.loginModel)` — this requires updating `LoginPage.apply` to accept `LoginPage.Model` instead of `LoginForm`. That change is in Task 11.

---

### Task 9: Rewrite `WebApp.scala`

**Files:**
- Modify: `frontend/src/main/scala/onparity/main/WebApp.scala`

- [ ] **Step 1: Replace `update` method only — keep `router`, `init`, `view`, `subscriptions` unchanged**

Replace the entire `update` method body:

```scala
  def update(model: Model): Msg => (Model, Cmd[Task, Msg]) =
    case Msg.NoOp => (model, Cmd.None)

    case Msg.LogMessage(msg) =>
      (model, PrettyLogger.info(msg))

    case Msg.Error(msg) =>
      (model, PrettyLogger.error(msg))

    case Msg.ToggleDarkMode =>
      (model.toggleDarkMode, Cmd.None)

    case Msg.GoToInternet(loc) =>
      (model, Nav.loadUrl(loc.url))

    case Msg.NavigateTo(page) =>
      val isLoginRequired = false
      if !isLoginRequired then (model.navigateTo(page), page.doNavigate(model))
      else (Model.init, Cmd.Emit(Msg.NavigateTo(Page.Login)))

    case Msg.RestoredSession(accessToken, refreshToken) =>
      (model.loginSuccess(accessToken, refreshToken), Cmd.Emit(Msg.DoNavigate(Page.Home)))

    case Msg.Logout =>
      (model.logout, LocalStorageHelper.cleanStorageWhenLogout |+| Cmd.Emit(Msg.NavigateTo(Page.Login)))

    case Msg.DoNavigate(page) =>
      (model.modify(_.currentPage).setTo(page), Nav.pushUrl[Task](page.path) |+| Flowbite.initCmd)

    case Msg.UnhandledRoute(path) =>
      (model, PrettyLogger.error("Unhandled route" + path))

    case Msg.SendHttpRequestWithAccessToken(f) =>
      (model, f(""))

    case Msg.NavigateToMaintainFundFlow(ff) =>
      (
        model.copy(maintainFundFlowModel = MaintainFundFlowView.Model(ff)),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainFundFlow))
      )

    case Msg.NavigateToAccounts(ff) =>
      (
        model.copy(accountsModel = AccountsView.Model(fundFlow = ff)),
        HttpHelper.fetchAccountsByFundFlowID(ff.id) |+|
          Cmd.Emit(Msg.NavigateTo(Page.Accounts)) |+|
          PrettyLogger.info("fetchAccountsByFundFlowID")
      )

    case msg => model.currentPage.update(model, msg)
```

Also add these imports at the top of `WebApp.scala` if not already present:
```scala
import onparity.view.pages.{AccountsView, MaintainFundFlowView}
```

---

### Task 10: Rewrite `HttpHelper.scala`

**Files:**
- Modify: `frontend/src/main/scala/onparity/util/HttpHelper.scala`

- [ ] **Step 1: Replace the entire file**

```scala
package onparity.util

import onparity.common.BuildInfo
import onparity.common.model.*
import HttpHelper.ResponseDecoders.*
import onparity.common.http.{BackendApiUrl, HttpPath}
import onparity.model.Msg
import onparity.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainFundFlowView, Welcome}
import tyrian.Cmd
import tyrian.http.*
import upickle.default.*
import zio.Task
import zio.interop.catz.*
import zio.json.*

object HttpHelper:

  private def sendWithAuthToken[A](request: Request[A], resultToMessage: Decoder[Msg]): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    PrettyLogger.info("Cmd.Emit") |+| Cmd.Emit(
      Msg.SendHttpRequestWithAccessToken(_ =>
        Http.send(request, resultToMessage)
      )
    )

  def login(username: String, password: String): Cmd[Task, Msg] = {
    val body    = LoginRequest(username, password)
    val request = Request.post(BackendApiUrl.login, Body.json(body.toJson))
    Http.send(request, loginDecoder)
  }

  val fetchServerMessage: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(BackendApiUrl.randomMessage, greetDecoder)

  val fetchServerMessage2: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(BackendApiUrl.randomMessage2, greetDecoder)

  val fetchFundFlows: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(HttpPath.fundFlows), fundFlowsDecoder)

  def createFundFlow(fundFlow: FundFlowPayload): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.post(HttpPath.url(HttpPath.createFundFlow), Body.json(write(fundFlow))), fundFlowCreatedDecoder)

  def fetchAccountsByFundFlowID(fundFlowID: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(s"${HttpPath.fundFlows}/$fundFlowID/accounts"), accountDecoder)

  val fetchReconAccounts: Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    fetchServerMessageCmd(HttpPath.url(HttpPath.accounts), accountDecoder)

  private def fetchServerMessageCmd(
    apiUrl:          String,
    resultToDecoder: Decoder[Msg]
  ): Cmd[Task, Msg.SendHttpRequestWithAccessToken] =
    sendWithAuthToken(Request.get(apiUrl), resultToDecoder) |+| PrettyLogger.info("http.send")

  object ResponseDecoders:

    val loginDecoder: Decoder[Msg] =
      given JsonDecoder[Either[LoginSuccess, LoginFailure]] =
        JsonDecoder[LoginSuccess] <+> JsonDecoder[LoginFailure]
      Decoder(
        _.body.fromJson[Either[LoginSuccess, LoginFailure]] match {
          case Right(Left(LoginSuccess(at, rt))) =>
            Msg.LoginMsg(LoginPage.Msg.LoginSuccess(at, rt))
          case Right(Right(LoginFailure(msg))) =>
            Msg.LoginMsg(LoginPage.Msg.LoginFailure(msg))
          case _ =>
            Msg.LoginMsg(LoginPage.Msg.LoginFailure("Failed to parse login response"))
        },
        error => Msg.LoginMsg(LoginPage.Msg.LoginFailure(s"Server error. Make sure server is running on ${BuildInfo.backendBaseUrl}"))
      )

    val greetDecoder: Decoder[Msg] = Decoder(
      onResponse =>
        onResponse.body.fromJson[RandomMessage] match {
          case Right(RandomMessage(text)) => Msg.HomeMsg(Welcome.Msg.GreetingFromBackend(text))
          case _                          => Msg.Logout
        },
      error => Msg.Error(error.toString())
    )

    val fundFlowsDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val ffPayload = read[Seq[FundFlowPayload]](onResponse.body)
          Msg.FundFlowsMsg(FundFlowsView.Msg.FundFlowsFromBackend(ffPayload)),
        error => Msg.Error(error.toString)
      )

    val fundFlowCreatedDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val ffPayload = read[FundFlowPayload](onResponse.body)
          Msg.MaintainFundFlowMsg(MaintainFundFlowView.Msg.FundFlowCreated(ffPayload)),
        error => Msg.Error(error.toString)
      )

    val accountDecoder: Decoder[Msg] =
      Decoder(
        onResponse =>
          val accountsPayload = read[Seq[AccountPayload]](onResponse.body)
          Msg.AccountsMsg(AccountsView.Msg.ReconAccountsFromBackend(accountsPayload)),
        error => Msg.Error(error.toString)
      )
```

Note: `fetchServerMessage` private helper is renamed `fetchServerMessageCmd` to avoid shadowing the public `val fetchServerMessage`.

---

## Phase 3 — Update `apply()` methods in view files

Now that `model.scala` and `Page.scala` use the page model types, update each `apply()` to accept the page `Model` and emit wrapped `Msg` variants.

---

### Task 11: Update `LoginPage.apply` to use `LoginPage.Model`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/Login.scala`

- [ ] **Step 1: Replace the `apply` method and the private `formField` helper**

The `apply` signature changes from `apply(state: LoginForm)` to `apply(model: Model)`. The `onInput` lambdas now emit `AppMsg.LoginMsg(Msg.UpdateUsername(...))` etc.

```scala
  private def formField(
    name: String,
    _value: String,
    _onInput: String => AppMsg,
    _type: "password" | "text"
  ): Html[AppMsg] =
    val icon         = if _type == "password" then Icons.lock else Icons.user
    val _placeholder = if _type == "password" then "••••••••" else ""
    val _id          = ElementIdGenerator.generate("login-")
    div()(
      label(`for` := _id, cls := "block mb-2 text-sm font-medium text-gray-900 dark:text-white")(name),
      div(cls := "relative")(
        div(cls := "absolute inset-y-0 start-0 flex items-center ps-3.5 pointer-events-none")(icon),
        input(
          onInput(_onInput),
          id          := _id,
          `type`      := _type,
          value       := _value,
          placeholder := _placeholder,
          cls         := "bg-gray-50 border border-gray-300 text-gray-900 text-sm rounded-lg focus:ring-purple-500 focus:border-purple-500 block w-full ps-10 p-2.5  dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400 dark:text-white dark:focus:ring-purple-500 dark:focus:border-purple-500"
        )
      )
    )

  def apply(model: Model): Html[AppMsg] =
    div(cls := "h-screen flex justify-center bg-base-150")(
      div(
        cls := "w-full max-w-sm my-auto p-4 bg-white border border-gray-200 rounded-lg shadow-sm sm:p-6 md:p-8 dark:bg-gray-800 dark:border-gray-700"
      )(
        form(cls := "space-y-6", attr("action", "#"))(
          h5(cls := "text-xl font-medium text-gray-900 dark:text-white")("Just click Login"),
          formField("Username", model.username, v => AppMsg.LoginMsg(Msg.UpdateUsername(v)), "text"),
          formField("Password", model.password, v => AppMsg.LoginMsg(Msg.UpdatePassword(v)), "password"),
          p(cls := "text-red-500")(model.error.getOrElse("")),
          div(cls := "flex items-start")(
            div(cls := "flex items-center h-5")(
              input(
                id     := "remember",
                `type` := "checkbox",
                cls    := "w-4 h-4 checked:bg-purple-500 dark:checked:bg-purple-400 border border-gray-300 rounded-sm bg-gray-50 focus:ring-3 focus:ring-purple-300 dark:bg-gray-700 dark:border-gray-600 dark:focus:ring-purple-600 dark:ring-offset-gray-800 dark:focus:ring-offset-gray-800"
              )
            ),
            label(`for` := "remember", cls := "ms-2 text-sm font-medium text-gray-900 dark:text-gray-300")("Remember me"),
            a(cls := "ms-auto text-sm text-purple-700 hover:underline dark:text-purple-400")("Forgot Password?")
          ),
          button(
            onClick(AppMsg.LoginMsg(Msg.Submit)),
            disabled(model.isSubmitDisabled),
            cls := s"w-full text-white bg-purple-700 hover:bg-purple-800 focus:ring-4 focus:outline-none focus:ring-purple-300 font-medium rounded-lg text-sm px-5 py-2.5 text-center dark:bg-purple-600 dark:hover:bg-purple-700 dark:focus:ring-purple-800 ${if model.isSubmitDisabled then "btn-disabled" else ""}"
          )("Login"),
          span(
            style(if model.isLoading then CSS.display("block") else CSS.display("none")),
            cls := "loading loading-ring loading-lg text-info"
          )()
        )
      )
    )
```

Also remove the now-unused import of `onparity.model.Model.LoginForm`.

---

### Task 12: Update `Welcome.apply` to use `Welcome.Model`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/Welcome.scala`

- [ ] **Step 1: Replace the `apply` method**

Signature changes from `apply(state: HomeState)` to `apply(model: Model)`. The `onClick` for the refresh button changes from `Msg.NextBackendMessage` to `AppMsg.HomeMsg(Msg.NextBackendMessage)`.

```scala
  def apply(model: Model): Html[AppMsg] =
    val tyrianLink =
      a(
        href   := "https://tyrian.indigoengine.io",
        rel    := "noopener noreferrer",
        target := "_blank",
        cls    := "font-mono text-purple-600 dark:text-purple-800 hover:underline"
      )("Tyrian")
    val flowbiteLink = a(href := "https://flowbite.com", rel := "noopener noreferrer", target := "_blank", cls := "text-blue-700 hover:underline")(
      "Flowbite"
    )

    val title = h1(cls := "mb-2 text-4xl font-bold tracking-tight text-gray-900 dark:text-white")(
      tyrianLink,
      text(" <+> "),
      flowbiteLink
    )

    div(cls := "flex flex-col items-center mb-8")(
      div(cls := "my-8")(title),
      div(
        cls := "max-w transition-transform duration-300 ease-in-out hover:-translate-y-2 bg-white border border-gray-200 rounded-lg shadow-xl dark:bg-gray-600"
      )(
        div(cls := "flex items-space-between items-center w-[60rem] h-[12rem] p-5 space-y-6 text-center")(
          div(cls := "mb-3 font-large text-gray-900 dark:text-gray-300")(
            div(cls := "flex flex-col space-y-4 w-[50rem]")(
              div(cls := "text-green-400 text-2xl font-semibold")("Message from server"),
              div(cls := "font-mono text-sm italic text-gray-600 dark:text-white break-words whitespace-normal")(
                if model.serverMessage.isEmpty then "Empty...is Server running ?" else model.serverMessage.get
              )
            )
          ),
          button(
            cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
            onClick(AppMsg.HomeMsg(Msg.NextBackendMessage))
          )(
            Icons.refresh
          )
        )
      ),
    )
```

Remove the now-unused import of `onparity.model.Model.HomeState`.

---

### Task 13: Update `FundFlowsView.apply` to use `FundFlowsView.Model`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/FundFlowsView.scala`

- [ ] **Step 1: Replace the `apply` method**

Signature changes from `apply(fundFlows: Seq[FundFlowPayload])` to `apply(model: Model)`. The Edit button changes from `Msg.SelectFundFlow(ff)` to `AppMsg.FundFlowsMsg(Msg.SelectFundFlow(ff))`.

```scala
  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "w-full max-w-4xl px-4")(
        div(cls := "flex justify-between items-center my-8")(
          h2(cls := "text-xl font-medium")(text("Fund Flows")),
          Buttons.linkButton("Create", AppMsg.NavigateTo(Page.MaintainFundFlow))
        ),
        Table.create(
          Seq("ID", "Name", "Created At", "Updated At", ""),
          model.fundFlows.map(ff =>
            Seq(
              text(ff.id),
              text(ff.name),
              text(ff.createdAt.get.toString),
              text(ff.updatedAt.get.toString),
              Buttons.textButton("Edit", AppMsg.FundFlowsMsg(Msg.SelectFundFlow(ff)))
            )
          )
        )
      )
    )
```

---

### Task 14: Update `MaintainFundFlowView.apply` to use `MaintainFundFlowView.Model`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/MaintainFundFlowView.scala`

- [ ] **Step 1: Replace the `apply` method**

Signature changes from `apply(ff: FundFlowPayload)` to `apply(model: Model)`. All Msg references become wrapped.

```scala
  def apply(model: Model): Html[AppMsg] =
    val ff = model.fundFlow
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Fund Flow")),
        Form.field[AppMsg](
          fieldID      = "name",
          labelName    = "Name",
          defaultValue = Some(ff.name),
          onChangFn    = Some(value => AppMsg.MaintainFundFlowMsg(Msg.FundFlowFormUpdate(ff.copy(name = value))))
        ),
        ff.createdAt
          .map(createdAt =>
            Form.field[AppMsg](
              fieldID      = "created_at",
              labelName    = "CreatedAt",
              defaultValue = Some(createdAt),
              enabled      = false
            )
          )
          .getOrElse(div()()),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", AppMsg.NavigateTo(Page.FundFlows)),
          Buttons.linkButton("Save", AppMsg.MaintainFundFlowMsg(Msg.CreateFundFlow(ff)))
        )
      )
    )
```

---

### Task 15: Update `AccountsView.apply` to use `AccountsView.Model`

**Files:**
- Modify: `frontend/src/main/scala/onparity/view/pages/AccountsView.scala`

- [ ] **Step 1: Replace the `apply` method**

Signature changes from `apply(fundFlowPayload: FundFlowPayload, accounts: Seq[AccountPayload])` to `apply(model: Model)`.

```scala
  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        span(onClick(AppMsg.NavigateTo(Page.FundFlows)))(text(s"${model.fundFlow.name}: Accounts View")),
        button(
          cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
          onClick(AppMsg.AccountsMsg(Msg.GetReconAccounts))
        )(Icons.user),
        Table.create(
          Seq("ID", "Name", "Balance"),
          model.accounts.map(account =>
            Seq(
              text(account.id),
              text(account.name),
              text(account.balance.toString)
            )
          )
        )
      )
    )
```

---

## Phase 4 — Compile and fix

---

### Task 16: Compile and fix errors

- [ ] **Step 1: Run the compiler**

```bash
sbt "frontend/compile"
```

- [ ] **Step 2: Fix any remaining errors**

Common errors to expect and their fixes:

- **`value loginForm is not a member of Model`** — a reference to the old `model.loginForm` was missed. Replace with `model.loginModel`.
- **`value fundFlows is not a member of Model`** — replace with `model.fundFlowsModel.fundFlows`.
- **`value selectedFundFlow is not a member of Model`** — replace with `model.maintainFundFlowModel.fundFlow` or `model.accountsModel.fundFlow` depending on context.
- **`value accounts is not a member of Model`** — replace with `model.accountsModel.accounts`.
- **`value homeState is not a member of Model`** — replace with `model.homeModel`.
- **Unresolved reference `Msg.FundFlowsFromBackend`** — these old variants no longer exist in top-level Msg; they were removed in Task 7. Find the reference and update to the wrapped form.
- **`modify` path errors** — `model.modify(_.loginForm.isLoading)` no longer valid; replace with `model.modify(_.loginModel.isLoading)` if using quicklens, or use `model.copy(loginModel = model.loginModel.copy(isLoading = true))`.

- [ ] **Step 3: Commit all changes**

```bash
git add -p   # stage changed files selectively
git commit -m "feat: page-scoped model and message architecture"
```

---

### Task 17: Smoke test in browser

- [ ] **Step 1: Start the dev server**

```bash
sbt "frontend/fastLinkJS"
# Then in a separate terminal:
cd frontend && npm run dev
```

- [ ] **Step 2: Verify these flows work**

1. Navigate to `/login` → login form renders, submit works, redirects to Home
2. Home page shows server message, refresh button fetches a new message
3. Navigate to Fund Flows → list renders
4. Click Edit on a fund flow → navigates to Maintain Fund Flow with the correct fund flow populated
5. Edit name field → save → fund flow updated
6. Click Cancel → returns to Fund Flows
7. Fund Flows → Accounts icon on a flow → navigates to Accounts and loads accounts list
