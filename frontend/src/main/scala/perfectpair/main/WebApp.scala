package perfectpair.main

import com.softwaremill.quicklens.*
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

  def main(args: Array[String]): Unit = launch("app") // mount the app to div with id="app"

  def router: Location => Msg =
    case loc: Location.Internal =>
      loc.pathName match
        case Route.Home(_) =>
          Msg.NavigateTo(Page.Home)

        case Route.ComponentDemo(page) =>
          Msg.NavigateTo(page)

        case path @ _ =>
          Msg.UnhandledRoute(path)

    case loc: Location.External => Msg.GoToInternet(loc)

  def init(flags: Map[String, String]): (Model, Cmd[Task, Msg]) =
    val initState = Model.init
    (initState, LocalStorageHelper.obtainSessionCmd)

  def update(model: Model): Msg => (Model, Cmd[Task, Msg]) =
    case Msg.NoOp => (model, Cmd.None)

    case Msg.LogMessage(msg) =>
      (model, PrettyLogger.info(msg))

    case Msg.Error(msg) =>
      (model.copy(error = Some(msg)), PrettyLogger.error(msg))

    case Msg.DismissError =>
      (model.copy(error = None), Cmd.None)

    case Msg.ToggleDarkMode =>
      (model.toggleDarkMode, Cmd.None)

    case Msg.GoToInternet(loc) =>
      (model, Nav.loadUrl(loc.url))

    case Msg.NavigateTo(page) =>
      val isLoginRequired = false
      if !isLoginRequired then (model.navigateTo(page).copy(error = None), page.doNavigate(model))
      else (Model.init, Cmd.Emit(Msg.NavigateTo(Page.Login)))

    case Msg.RestoredSession(accessToken, refreshToken) =>
      (
        model.loginSuccess(accessToken, refreshToken),
        HttpHelper.fetchFundFlows |+| Cmd.Emit(Msg.DoNavigate(Page.Home))
      )

    case Msg.Logout =>
      (model.logout, LocalStorageHelper.cleanStorageWhenLogout |+| Cmd.Emit(Msg.NavigateTo(Page.Login)))

    case Msg.ToggleFundFlowNav =>
      (model.copy(fundFlowNavOpen = !model.fundFlowNavOpen), Cmd.None)

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

    case Msg.NavigateToFundFlow(ff) =>
      (
        model.copy(fundFlowViewModel = FundFlowView.Model(ff)),
        HttpHelper.fetchAccountsForFundFlowView(ff.id.get) |+|
          HttpHelper.fetchMovementSpecsForFundFlowView(ff.id.get) |+|
          Cmd.Emit(Msg.NavigateTo(Page.FundFlow))
      )

    case Msg.NavigateToAccounts(fundFlow) =>
      (
        model.copy(accountsModel = AccountsView.Model(fundFlow = fundFlow)),
        HttpHelper.fetchAccountsByFundFlowID(fundFlow.id.get) |+|
          Cmd.Emit(Msg.NavigateTo(Page.Accounts)) |+|
          PrettyLogger.info(s"fetchAccountsByFundFlowID: ffID = ${fundFlow.id}")
      )

    case Msg.NavigateToMovementSpecs(fundFlow) =>
      (
        model.copy(movementSpecsModel = MovementSpecsView.Model(fundFlow = fundFlow)),
        HttpHelper.fetchMovementSpecsByFundFlowID(fundFlow.id.get) |+|
          Cmd.Emit(Msg.NavigateTo(Page.MovementSpecs)) |+|
          PrettyLogger.info("fetchMovementSpecsByFundFlowID")
      )

    case Msg.NavigateToReconSpecs(account) =>
      (
        model.copy(reconSpecsModel = ReconSpecsView.Model(account = account)),
        HttpHelper.fetchReconSpecsByAccountID(account.id.get) |+|
          HttpHelper.fetchMovementSpecsForReconView(account.fundFlowId) |+|
          Cmd.Emit(Msg.NavigateTo(Page.ReconSpecs)) |+|
          PrettyLogger.info("NavigateToReconSpecs")
      )

    case Msg.NavigateToMaintainReconSpec(account, movementSpecs, reconSpec) =>
      (
        model.copy(maintainReconSpecModel =
          MaintainReconSpecView.Model(
            account       = account,
            movementSpecs = movementSpecs,
            reconSpec     = reconSpec,
            ruleWidget    = RuleWidget.fromRule(reconSpec.map(_.rule).getOrElse(NoOpRule))
          )
        ),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainReconSpec))
      )

    case Msg.NavigateToMaintainMovementSpec(fundFlow, accounts, movementSpec) =>
      (
        model.copy(maintainMovementSpecModel =
          MaintainMovementSpecsView.Model(
            fundFlow         = fundFlow,
            accounts         = accounts,
            movementSpec     = movementSpec,
            dataSourceWidget = DataSourceConfigWidget.fromDataSourceConfig(movementSpec.flatMap(_.dataSourceConfig))
          )
        ),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainMovementSpec))
      )

    case Msg.NavigateToIngest(account, reconSpec, movementSpecs) =>
      (
        model.copy(ingestModel =
          IngestView.Model(
            account       = account,
            reconSpec     = reconSpec,
            movementSpecs = movementSpecs
          )
        ),
        Cmd.Emit(Msg.NavigateTo(Page.Ingest))
      )

    case Msg.NavigateToUnreconciledMovements(movementSpec, accounts, fundFlow) =>
      (
        model.copy(unreconciledMovementsModel =
          UnreconciledMovementsView.Model(
            fundFlow     = fundFlow,
            movementSpec = movementSpec,
            accounts     = accounts
          )
        ),
        Cmd.Emit(Msg.NavigateTo(Page.UnreconciledMovements))
      )

    case Msg.NavigateToReconciliations(reconSpec, account) =>
      (
        model.copy(reconciliationsModel = ReconciliationsView.Model(account = account, reconSpec = reconSpec)),
        Cmd.Emit(Msg.NavigateTo(Page.Reconciliations))
      )

    case Msg.NavigateToMaintainAccount(fundFlow, account) =>
      println(s"Msg.NavigateToMaintainAccount fundflow: $fundFlow, account:$account")
      (
        model.copy(maintainAccountModel = MaintainAccountView.Model(fundFlow, account)),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainAccount))
      )

    case msg => model.currentPage.update(model, msg)

  def view(model: Model): Html[Msg] =
    val pageContent = model.currentPage.render(model)
    MainContainer(
      pageContent,
      model.isDarkMode,
      model.isLoggedIn,
      model.error,
      model.fundFlowsModel.fundFlows,
      model.fundFlowNavOpen
    )

  def subscriptions(model: Model): Sub[Task, Msg] = Sub.None
