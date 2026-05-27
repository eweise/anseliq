package perfectpair.main

import perfectpair.common.model.NoOpRule
import perfectpair.model.*
import perfectpair.page.*
import perfectpair.route.*
import perfectpair.util.*
import perfectpair.view.MainContainer
import perfectpair.view.components.{DataSourceConfigWidget, RuleWidget}
import perfectpair.view.pages.*
import tyrian.*
import zio.*
import zio.interop.catz.*

import scala.scalajs.js.annotation.*

@JSExportTopLevel("TyrianApp")
object WebApp extends TyrianZIOApp[Msg, Model]:

  def main(args: Array[String]): Unit = launch("app")

  def router: Location => Msg =
    case loc: Location.Internal =>
      loc.pathName match
        case Route.Home(_)             => Msg.NavigateTo(Page.Home)
        case Route.ComponentDemo(page) => Msg.NavigateTo(page)
        case path @ _                  => Msg.UnhandledRoute(path)
    case loc: Location.External => Msg.GoToInternet(loc)

  def init(flags: Map[String, String]): (Model, Cmd[Task, Msg]) =
    (Model.init, LocalStorageHelper.obtainSessionCmd)

  def update(model: Model): Msg => (Model, Cmd[Task, Msg]) =
    case Msg.NoOp => (model, Cmd.None)

    case Msg.LogMessage(msg) =>
      (model, PrettyLogger.info(msg))

    case Msg.Error(msg) =>
      (model.copy(shared = model.shared.copy(error = Some(msg))), PrettyLogger.error(msg))

    case Msg.DismissError =>
      (model.copy(shared = model.shared.copy(error = None)), Cmd.None)

    case Msg.ToggleDarkMode =>
      (model.toggleDarkMode, Cmd.None)

    case Msg.GoToInternet(loc) =>
      (model, Nav.loadUrl(loc.url))

    case Msg.NavigateTo(page) =>
      val (newPageState, extraCmd) = page match
        case Page.Login          => (PageState.Login(), Cmd.None)
        case Page.Home           => (PageState.Home(), HttpHelper.fetchServerMessage)
        case Page.FundFlows      => (PageState.FundFlows(), HttpHelper.fetchFundFlows)
        case Page.MaintainFundFlow => (PageState.MaintainFundFlow(), Cmd.None)
        case Page.Jobs           =>
          val m = JobsView.Model()
          (PageState.Jobs(m), HttpHelper.fetchJobsByType(m.selectedJobType.toString))
        case _                   => (model.pageState, Cmd.None)
      (
        model.copy(shared = model.shared.copy(error = None), pageState = newPageState),
        extraCmd |+| PrettyLogger.info("Navigating To Page") |+| Cmd.emit(Msg.DoNavigate(page))
      )

    case Msg.RestoredSession(accessToken, refreshToken) =>
      (
        model.loginSuccess(accessToken, refreshToken).copy(pageState = PageState.Home()),
        HttpHelper.fetchFundFlows |+| Cmd.emit(Msg.DoNavigate(Page.Home))
      )

    case Msg.Logout =>
      (model.logout, LocalStorageHelper.cleanStorageWhenLogout |+| Cmd.emit(Msg.NavigateTo(Page.Login)))

    case Msg.ToggleFundFlowNav =>
      (model.copy(shared = model.shared.copy(fundFlowNavOpen = !model.shared.fundFlowNavOpen)), Cmd.None)

    case Msg.DoNavigate(page) =>
      (model, Nav.pushUrl[Task](page.path) |+| Flowbite.initCmd)

    case Msg.UnhandledRoute(path) =>
      (model, PrettyLogger.error("Unhandled route" + path))

    case Msg.SendHttpRequestWithAccessToken(f) =>
      (model, f(""))

    case Msg.NavigateToMaintainFundFlow(ff) =>
      (
        model.copy(pageState = PageState.MaintainFundFlow(MaintainFundFlowView.Model(ff))),
        Cmd.emit(Msg.DoNavigate(Page.MaintainFundFlow))
      )

    case Msg.NavigateToFundFlow(ff) =>
      (
        model.copy(pageState = PageState.FundFlow(FundFlowView.Model(ff))),
        HttpHelper.fetchAccountsForFundFlowView(ff.id.get) |+|
          HttpHelper.fetchMovementSpecsForFundFlowView(ff.id.get) |+|
          Cmd.emit(Msg.DoNavigate(Page.FundFlow))
      )

    case Msg.NavigateToAccounts(fundFlow) =>
      (
        model.copy(pageState = PageState.Accounts(AccountsView.Model(fundFlow = fundFlow))),
        HttpHelper.fetchAccountsByFundFlowID(fundFlow.id.get) |+|
          PrettyLogger.info(s"fetchAccountsByFundFlowID: ffID = ${fundFlow.id}") |+|
          Cmd.emit(Msg.DoNavigate(Page.Accounts))
      )

    case Msg.NavigateToReconSpecs(account) =>
      (
        model.copy(pageState = PageState.ReconSpecs(ReconSpecsView.Model(account = account))),
        HttpHelper.fetchReconSpecsByAccountID(account.id.get) |+|
          HttpHelper.fetchMovementSpecsForReconView(account.fundFlowId) |+|
          PrettyLogger.info("NavigateToReconSpecs") |+|
          Cmd.emit(Msg.DoNavigate(Page.ReconSpecs))
      )

    case Msg.NavigateToMaintainReconSpec(account, movementSpecs, reconSpec, returnTo) =>
      (
        model.copy(pageState = PageState.MaintainReconSpec(
          MaintainReconSpecView.Model(
            account       = account,
            movementSpecs = movementSpecs,
            reconSpec     = reconSpec,
            ruleWidget    = RuleWidget.fromRule(reconSpec.map(_.rule).getOrElse(NoOpRule)),
            returnTo      = returnTo
          )
        )),
        Cmd.emit(Msg.DoNavigate(Page.MaintainReconSpec))
      )

    case Msg.NavigateToMaintainMovementSpec(fundFlow, accounts, movementSpec, returnTo) =>
      (
        model.copy(pageState = PageState.MaintainMovementSpec(
          MaintainMovementSpecView.Model(
            fundFlow         = fundFlow,
            accounts         = accounts,
            movementSpec     = movementSpec,
            dataSourceWidget = DataSourceConfigWidget.fromDataSourceConfig(movementSpec.flatMap(_.dataSourceConfig)),
            returnTo         = returnTo
          )
        )),
        Cmd.emit(Msg.DoNavigate(Page.MaintainMovementSpec))
      )

    case Msg.NavigateToIngest(account, reconSpec, movementSpecs) =>
      (
        model.copy(pageState = PageState.Ingest(
          IngestView.Model(account = account, reconSpec = reconSpec, movementSpecs = movementSpecs)
        )),
        Cmd.emit(Msg.DoNavigate(Page.Ingest))
      )

    case Msg.NavigateToUnreconciledMovements(movementSpec, accounts, fundFlow) =>
      (
        model.copy(pageState = PageState.UnreconciledMovements(
          UnreconciledMovementsView.Model(fundFlow = fundFlow, movementSpec = movementSpec, accounts = accounts)
        )),
        HttpHelper.fetchUnreconciledMovements(movementSpec.id.get) |+|
          Cmd.emit(Msg.DoNavigate(Page.UnreconciledMovements))
      )

    case Msg.NavigateToReconciliations(reconSpec, account) =>
      (
        model.copy(pageState = PageState.Reconciliations(
          ReconciliationsView.Model(account = account, reconSpec = reconSpec)
        )),
        HttpHelper.fetchReconciliationsByReconSpecID(reconSpec.id.get) |+|
          Cmd.emit(Msg.DoNavigate(Page.Reconciliations))
      )

    case Msg.NavigateToMaintainAccount(fundFlow, account, returnTo) =>
      println(s"Msg.NavigateToMaintainAccount fundflow: $fundFlow, account:$account")
      (
        model.copy(pageState = PageState.MaintainAccount(MaintainAccountView.Model(fundFlow, account, returnTo))),
        Cmd.emit(Msg.DoNavigate(Page.MaintainAccount))
      )

    // Page message dispatchers
    case Msg.LoginMsg(sub) =>
      model.pageState match
        case PageState.Login(m) =>
          val (m2, cmd) = LoginPage.update(m, sub)
          (model.copy(pageState = PageState.Login(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.HomeMsg(sub) =>
      model.pageState match
        case PageState.Home(m) =>
          val (m2, cmd) = Welcome.update(m, sub)
          (model.copy(pageState = PageState.Home(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.FundFlowsMsg(sub) =>
      val shared2 = sub match
        case FundFlowsView.Msg.FundFlowsFromBackend(ff) => model.shared.copy(fundFlows = ff)
        case _                                           => model.shared
      model.pageState match
        case PageState.FundFlows(m) =>
          val (m2, cmd) = FundFlowsView.update(m, sub)
          (model.copy(pageState = PageState.FundFlows(m2), shared = shared2), cmd)
        case _ => (model.copy(shared = shared2), Cmd.None)

    case Msg.MaintainFundFlowMsg(sub) =>
      model.pageState match
        case PageState.MaintainFundFlow(m) =>
          val (m2, cmd) = MaintainFundFlowView.update(m, sub)
          (model.copy(pageState = PageState.MaintainFundFlow(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.AccountsMsg(sub) =>
      println("AccountsMsg = " + sub)
      model.pageState match
        case PageState.Accounts(m) =>
          val (m2, cmd) = AccountsView.update(m, sub)
          (model.copy(pageState = PageState.Accounts(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.MaintainAccountMsg(sub) =>
      model.pageState match
        case PageState.MaintainAccount(m) =>
          val (m2, cmd) = MaintainAccountView.update(m, sub)
          (model.copy(pageState = PageState.MaintainAccount(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.MaintainMovementSpecsMsg(sub) =>
      model.pageState match
        case PageState.MaintainMovementSpec(m) =>
          val (m2, cmd) = MaintainMovementSpecView.update(m, sub)
          (model.copy(pageState = PageState.MaintainMovementSpec(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.ReconSpecsMsg(sub) =>
      model.pageState match
        case PageState.ReconSpecs(m) =>
          val (m2, cmd) = ReconSpecsView.update(m, sub)
          (model.copy(pageState = PageState.ReconSpecs(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.MaintainReconSpecMsg(sub) =>
      model.pageState match
        case PageState.MaintainReconSpec(m) =>
          val (m2, cmd) = MaintainReconSpecView.update(m, sub)
          (model.copy(pageState = PageState.MaintainReconSpec(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.IngestViewMsg(sub) =>
      model.pageState match
        case PageState.Ingest(m) =>
          val (m2, cmd) = IngestView.update(m, sub)
          (model.copy(pageState = PageState.Ingest(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.JobsMsg(sub) =>
      model.pageState match
        case PageState.Jobs(m) =>
          val (m2, cmd) = JobsView.update(m, sub)
          (model.copy(pageState = PageState.Jobs(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.UnreconciledMovementsMsg(sub) =>
      model.pageState match
        case PageState.UnreconciledMovements(m) =>
          val (m2, cmd) = UnreconciledMovementsView.update(m, sub)
          (model.copy(pageState = PageState.UnreconciledMovements(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.ReconciliationsMsg(sub) =>
      model.pageState match
        case PageState.Reconciliations(m) =>
          val (m2, cmd) = ReconciliationsView.update(m, sub)
          (model.copy(pageState = PageState.Reconciliations(m2)), cmd)
        case _ => (model, Cmd.None)

    case Msg.FundFlowViewMsg(sub) =>
      model.pageState match
        case PageState.FundFlow(m) =>
          val (m2, cmd) = FundFlowView.update(m, sub)
          (model.copy(pageState = PageState.FundFlow(m2)), cmd)
        case _ => (model, Cmd.None)

  def view(model: Model): Html[Msg] =
    val pageContent = model.pageState match
      case PageState.Login(m)                 => LoginPage(m)
      case PageState.Home(m)                  => Welcome(m)
      case PageState.FundFlows(m)             => FundFlowsView(m)
      case PageState.FundFlow(m)              => FundFlowView(m)
      case PageState.MaintainFundFlow(m)      => MaintainFundFlowView(m)
      case PageState.Accounts(m)              => AccountsView(m)
      case PageState.MaintainAccount(m)       =>
        println(s"render = $m")
        MaintainAccountView(m)
      case PageState.MaintainMovementSpec(m)  => MaintainMovementSpecView(m)
      case PageState.ReconSpecs(m)            => ReconSpecsView(m)
      case PageState.MaintainReconSpec(m)     => MaintainReconSpecView(m)
      case PageState.Ingest(m)                => IngestView(m)
      case PageState.Jobs(m)                  => JobsView(m)
      case PageState.UnreconciledMovements(m) => UnreconciledMovementsView(m)
      case PageState.Reconciliations(m)       => ReconciliationsView(m)
    MainContainer(
      pageContent,
      model.shared.isDarkMode,
      model.isLoggedIn,
      model.shared.error,
      model.shared.fundFlows,
      model.shared.fundFlowNavOpen
    )

  def subscriptions(model: Model): Sub[Task, Msg] = Sub.None
