package perfectpair.page

import tyrian.Cmd
import tyrian.Html
import zio.*
import zio.interop.catz.*
import perfectpair.model.*
import perfectpair.view.pages.*
import perfectpair.util.{HttpHelper, PrettyLogger}

sealed trait Page {
  val path: String
  val render: Model => Html[Msg]
  val isSecured: Boolean = false
  protected def beforeEnter(model: Model): Cmd[Task, Msg] = Cmd.None

  def doNavigate(model: Model): Cmd[Task, Msg] =
    beforeEnter(model) |+| PrettyLogger.info("Navigating To Page") |+| Cmd.emit(Msg.DoNavigate(this))

  def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg])
}

object Page {
  object Login extends Page {
    val path: String = "/login"
    val render: Model => Html[Msg] = m => LoginPage(m.loginModel)
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.LoginMsg(sub) =>
        val (sub2, cmd) = LoginPage.update(model.loginModel, sub)
        (model.copy(loginModel = sub2), cmd)
      case _ => (model, Cmd.None)
  }

  object Home extends Page {
    val path: String = "/"
    val render: Model => Html[Msg] = m => Welcome(m.homeModel)
    override protected def beforeEnter(model: Model): Cmd[Task, Msg] = HttpHelper.fetchServerMessage
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.HomeMsg(sub) =>
        val (sub2, cmd) = Welcome.update(model.homeModel, sub)
        (model.copy(homeModel = sub2), cmd)
      case _ => (model, Cmd.None)
  }

  object FundFlows extends Page {
    val path: String = "/components/fund-flows"
    val render: Model => Html[Msg] = m => FundFlowsView(m.fundFlowsModel)
    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Fund Flows page") |+| HttpHelper.fetchFundFlows
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.FundFlowsMsg(sub) =>
        val (sub2, cmd) = FundFlowsView.update(model.fundFlowsModel, sub)
        (model.copy(fundFlowsModel = sub2), cmd)
      case _ => (model, Cmd.None)
  }

  object MaintainFundFlow extends Page {
    val path: String = "/components/maintain-fund-flow"
    val render: Model => Html[Msg] = m => MaintainFundFlowView(m.maintainFundFlowModel)
    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Maintain Fund Flow page")
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.MaintainFundFlowMsg(sub) =>
        val (sub2, cmd) = MaintainFundFlowView.update(model.maintainFundFlowModel, sub)
        (model.copy(maintainFundFlowModel = sub2), cmd)
      case _ => (model, Cmd.None)
  }

  object Accounts extends Page {
    val path: String = "/components/accounts"
    val render: Model => Html[Msg] = m => AccountsView(m.accountsModel)
    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Accounts page")
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.AccountsMsg(sub) =>
        val (sub2, cmd) = AccountsView.update(model.accountsModel, sub)
        (model.copy(accountsModel = sub2), cmd)
      case _ => (model, Cmd.None)
  }

  object Toggle extends Page {
    val path: String = "/components/toggle"
    val render: Model => Html[Msg] = m => ToggleView(m.toggleModel)
    override protected def beforeEnter(model: Model): Cmd[Task, Msg] =
      PrettyLogger.info("Entering Toggle page")
    def update(model: Model, msg: Msg): (Model, Cmd[Task, Msg]) = msg match
      case Msg.ToggleMsg(sub) =>
        val (sub2, cmd) = ToggleView.update(model.toggleModel, sub)
        (model.copy(toggleModel = sub2), cmd)
      case _ => (model, Cmd.None)
  }
}
