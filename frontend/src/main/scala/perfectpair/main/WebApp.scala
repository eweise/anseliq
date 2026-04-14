package perfectpair.main

import com.softwaremill.quicklens.*
import perfectpair.model.*
import perfectpair.page.*
import perfectpair.route.*
import perfectpair.util.*
import perfectpair.view.MainContainer
import perfectpair.view.pages.{AccountsView, MaintainAccountView, MaintainFundFlowView}
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

    case Msg.NavigateToAccounts(ffID) =>
      (
        model.copy(accountsModel = AccountsView.Model()),
        HttpHelper.fetchAccountsByFundFlowID(ffID) |+|
          Cmd.Emit(Msg.NavigateTo(Page.Accounts)) |+|
          PrettyLogger.info("fetchAccountsByFundFlowID")
      )

    case Msg.NavigateToMaintainAccount(account) =>
      (
        model.copy(maintainAccountModel = MaintainAccountView.Model(account = account.get)),
        Cmd.Emit(Msg.NavigateTo(Page.MaintainAccount))
      )

    case msg => model.currentPage.update(model, msg)

  def view(model: Model): Html[Msg] =
    val pageContent = model.currentPage.render(model)
    MainContainer(pageContent, model.isDarkMode, model.isLoggedIn)

  def subscriptions(model: Model): Sub[Task, Msg] = Sub.None
