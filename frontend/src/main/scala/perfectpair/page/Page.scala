package perfectpair.page

import tyrian.Cmd
import tyrian.Html
import zio.*
import zio.interop.catz.*
import perfectpair.model.*
import perfectpair.view.pages.*
import perfectpair.util.{HttpHelper, PrettyLogger}

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
