package com.eweise.perfectpair.model

import com.eweise.perfectpair.common.model.AdapterModel.AccountPayload
import com.eweise.perfectpair.common.model.{LoginFailure, ReconciliationAccountPayload}
import com.eweise.perfectpair.common.util.JwtHelper
import tyrian.Location
import tyrian.Cmd
import tyrian.http.*
import zio.json.*
import zio.Task
import zio.json.ast.Json
import com.eweise.perfectpair.page.*
import com.eweise.perfectpair.model.Model.User
import com.eweise.perfectpair.model.Model.LoginForm
import com.eweise.perfectpair.model.Model.HomeState
import com.eweise.perfectpair.model.Model.emptyLoginForm

enum Msg {
  case NoOp
  case LogMessage(msg: String)
  case NavigateTo(page: Page)
  case DoNavigate(page: Page)
  case UnhandledRoute(path: String)
  case GoToInternet(loc: Location.External)
  case ToggleDarkMode

  // with backend integration only
  case Error(msg: String)
  case SendHttpRequestWithAccessToken(sendWithAccessToken: String => Cmd[Task, Msg])
  case LoginSuccess(accessToken: String, refreshToken: String)
  case LoginFailure(message: String)
  case RestoredSession(accessToken: String, refreshToken: String)
  case UpdateLoginForm(form: LoginForm)
  case SubmitLogin
  case NextBackendMessage
  case GreetingFromBackend(text: String)
  case GetReconAccounts
  case ReconAccountsFromBackend(accounts: Seq[AccountPayload])
  case Logout
}

/**
 * All frontend states is stored here
 */
case class Model(
  currentPage: Page,
  user: Option[User],
  loginForm: LoginForm,
  homeState: HomeState,
  isDarkMode: Boolean,
  reconAccounts: Seq[AccountPayload]
) {
  def toggleDarkMode: Model =
    copy(isDarkMode = !isDarkMode)

  def isLoggedIn: Boolean = user.isDefined

  def navigateTo(page: Page): Model = copy(currentPage = page)

  def loginSuccess(accessToken: String, refreshToken: String): Model =
    JwtHelper.getUnverifiedUserClaim(accessToken).map(claim => User(claim.username, accessToken, refreshToken)) match
      case Right(user) =>
        this.copy(user = Some(user), loginForm = emptyLoginForm)
      case Left(_) =>
        this
  def logout: Model = copy(user = None)
}

object Model {
  case class User(username: String, accessToken: String, refreshToken: String)

  /**
   * Store Login page state
   */
  case class LoginForm(username: String, password: String, error: Option[String], isLoading: Boolean) {
    def isSubmitDisabled: Boolean = username.isBlank() || password.isBlank()
  }

  /**
   * Store Home page state
   */
  case class HomeState(serverMessage: Option[String])

  val emptyLoginForm = LoginForm("Scala", "nopassword", None, false)
  val emptyHomeState = HomeState(None)
  val init: Model = Model(
    Page.Login,
    user = None,
    emptyLoginForm,
    emptyHomeState,
    isDarkMode = false,
    reconAccounts = Seq()
  )
}
