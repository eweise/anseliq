package perfectpair.model

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload}
import perfectpair.common.util.JwtHelper
import perfectpair.page.Page
import perfectpair.view.pages.*
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
  case DismissError
  case SendHttpRequestWithAccessToken(sendWithAccessToken: String => Cmd[Task, Msg])
  case RestoredSession(accessToken: String, refreshToken: String)
  case Logout

  // Navigation with context (carry page state to destination)
  case NavigateToMaintainFundFlow(ff: FundFlowPayload)
  case NavigateToAccounts(fundFlow: FundFlowPayload)
  case NavigateToMovementSpecs(fundFlow: FundFlowPayload)
  case NavigateToMaintainAccount(fundFlow: FundFlowPayload, account: Option[AccountPayload])
  case NavigateToMaintainMovementSpec(fundFlow: FundFlowPayload, accounts: Seq[AccountPayload], movementSpec: Option[MovementSpecPayload])

  // Page message wrappers
  case LoginMsg(msg: LoginPage.Msg)
  case HomeMsg(msg: Welcome.Msg)
  case FundFlowsMsg(msg: FundFlowsView.Msg)
  case MaintainFundFlowMsg(msg: MaintainFundFlowView.Msg)
  case AccountsMsg(msg: AccountsView.Msg)
  case MaintainAccountMsg(msg: MaintainAccountView.Msg)
  case MovementSpecsMsg(msg: MovementSpecsView.Msg)
  case MaintainMovementSpecsMsg(msg: MaintainMovementSpecsView.Msg)
  case ToggleMsg(msg: ToggleView.Msg)

case class User(username: String, accessToken: String, refreshToken: String)

case class Model(
  currentPage:           Page,
  user:                  Option[User],
  isDarkMode:            Boolean,
  loginModel:            LoginPage.Model,
  homeModel:             Welcome.Model,
  fundFlowsModel:        FundFlowsView.Model,
  maintainFundFlowModel: MaintainFundFlowView.Model,
  accountsModel:         AccountsView.Model,
  maintainAccountModel:  MaintainAccountView.Model,
  movementSpecsModel:          MovementSpecsView.Model,
  maintainMovementSpecModel:   MaintainMovementSpecsView.Model = MaintainMovementSpecsView.Model(),
  toggleModel:           ToggleView.Model = ToggleView.Model(),
  error:                 Option[String]   = None
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
    accountsModel         = AccountsView.Model(),
    maintainAccountModel =
      MaintainAccountView.Model(), // fixme. I don't think these models should be initialized with default payloads
    movementSpecsModel         = MovementSpecsView.Model(),
    maintainMovementSpecModel  = MaintainMovementSpecsView.Model()
  )
