package perfectpair.model

import perfectpair.common.model.{AccountPayload, FundFlowPayload, MovementSpecPayload, ReconciliationSpecPayload}
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
  case NavigateToMaintainAccount(fundFlow: FundFlowPayload, account: Option[AccountPayload], returnTo: Msg = Msg.NoOp)
  case NavigateToMaintainMovementSpec(
    fundFlow:     FundFlowPayload,
    accounts:     Seq[AccountPayload],
    movementSpec: Option[MovementSpecPayload],
    returnTo:     Msg = Msg.NoOp
  )
  case NavigateToReconSpecs(account: AccountPayload)
  case NavigateToMaintainReconSpec(
    account:       AccountPayload,
    movementSpecs: Seq[MovementSpecPayload],
    reconSpec:     Option[ReconciliationSpecPayload],
    returnTo:      Msg = Msg.NoOp
  )
  case NavigateToIngest(
    account:       AccountPayload,
    reconSpec:     ReconciliationSpecPayload,
    movementSpecs: Seq[MovementSpecPayload]
  )
  case NavigateToUnreconciledMovements(
    movementSpec: MovementSpecPayload,
    accounts:     Seq[AccountPayload],
    fundFlow:     FundFlowPayload
  )
  case NavigateToReconciliations(reconSpec: ReconciliationSpecPayload, account: AccountPayload)
  case ToggleFundFlowNav
  case NavigateToFundFlow(ff: FundFlowPayload)

  // Page message wrappers
  case LoginMsg(msg: LoginPage.Msg)
  case HomeMsg(msg: Welcome.Msg)
  case FundFlowsMsg(msg: FundFlowsView.Msg)
  case MaintainFundFlowMsg(msg: MaintainFundFlowView.Msg)
  case AccountsMsg(msg: AccountsView.Msg)
  case MaintainAccountMsg(msg: MaintainAccountView.Msg)
  case MaintainMovementSpecsMsg(msg: MaintainMovementSpecView.Msg)
  case ReconSpecsMsg(msg: ReconSpecsView.Msg)
  case MaintainReconSpecMsg(msg: MaintainReconSpecView.Msg)
  case IngestViewMsg(msg: IngestView.Msg)
  case JobsMsg(msg: JobsView.Msg)
  case UnreconciledMovementsMsg(msg: UnreconciledMovementsView.Msg)
  case ReconciliationsMsg(msg: ReconciliationsView.Msg)
  case FundFlowViewMsg(msg: FundFlowView.Msg)

case class User(username: String, accessToken: String, refreshToken: String)

case class SharedState(
  user:            Option[User]         = None,
  isDarkMode:      Boolean              = false,
  error:           Option[String]       = None,
  fundFlowNavOpen: Boolean              = false,
  fundFlows:       Seq[FundFlowPayload] = Seq()
)

enum PageState:
  case Login(model: LoginPage.Model = LoginPage.Model.init)
  case Home(model: Welcome.Model = Welcome.Model())
  case FundFlows(model: FundFlowsView.Model = FundFlowsView.Model())
  case MaintainFundFlow(model: MaintainFundFlowView.Model = MaintainFundFlowView.Model())
  case Accounts(model: AccountsView.Model = AccountsView.Model())
  case MaintainAccount(model: MaintainAccountView.Model = MaintainAccountView.Model())
  case MaintainMovementSpec(model: MaintainMovementSpecView.Model = MaintainMovementSpecView.Model())
  case ReconSpecs(model: ReconSpecsView.Model = ReconSpecsView.Model())
  case MaintainReconSpec(model: MaintainReconSpecView.Model = MaintainReconSpecView.Model())
  case Ingest(model: IngestView.Model = IngestView.Model())
  case Jobs(model: JobsView.Model = JobsView.Model())
  case UnreconciledMovements(model: UnreconciledMovementsView.Model = UnreconciledMovementsView.Model())
  case Reconciliations(model: ReconciliationsView.Model = ReconciliationsView.Model())
  case FundFlow(model: FundFlowView.Model = FundFlowView.Model())

case class Model(
  shared:    SharedState,
  pageState: PageState
):
  def isLoggedIn: Boolean = shared.user.isDefined

  def loginSuccess(accessToken: String, refreshToken: String): Model =
    JwtHelper.getUnverifiedUserClaim(accessToken).map(claim => User(claim.username, accessToken, refreshToken)) match
      case Right(u) => copy(shared = shared.copy(user = Some(u)))
      case Left(_)  => this

  def logout: Model         = copy(shared = shared.copy(user = None))
  def toggleDarkMode: Model = copy(shared = shared.copy(isDarkMode = !shared.isDarkMode))

object Model:
  val init: Model = Model(
    shared    = SharedState(),
    pageState = PageState.Login()
  )
