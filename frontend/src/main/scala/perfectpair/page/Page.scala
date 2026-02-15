package perfectpair.page

import tyrian.Cmd
import tyrian.Html
import tyrian.Html.div

import java.util.UUID
import tyrian.cmds.Logger
import zio.*
import zio.interop.catz.*
import perfectpair.model.*
import perfectpair.view.pages.*
import perfectpair.common.model.FundFlowPayload
import perfectpair.model.{Model, Msg}
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.pages.{AccountsView, FundFlowsView, LoginPage, MaintainFundFlowView, ToggleView, Welcome}

enum Page(
  val path: String,
  val render: Model => Html[Msg],
  beforeEnter: Model => Cmd[Task, Msg] = _ => Cmd.None, // e.g: side effect for loading data
  val isSecured: Boolean = false
) {
  def doNavigate(model: Model): Cmd[Task, Msg] = beforeEnter(model) |+| PrettyLogger.info("Navigating To Page") |+| Cmd.emit(Msg.DoNavigate(this))

  case Login extends Page("/login", model => LoginPage(model.loginForm), isSecured = false)
  case Home  extends Page("/", model => Welcome(model.homeState), _ => HttpHelper.fetchServerMessage)
  case FundFlows
      extends Page(
        "/components/fund-flows",
        model => FundFlowsView(model.fundFlows),
        _ => PrettyLogger.info("Entering Fund Flows page") |+| HttpHelper.fetchFundFlows
      )
  case MaintainFundFlow
      extends Page("/components/maintain-fund-flow", model => MaintainFundFlowView(model.selectedFundFlow.getOrElse(FundFlowPayload())), _ => PrettyLogger.info("Entering Maintain Fund Flow page"))
  case Accounts
      extends Page(
        "/components/accounts",
        model => AccountsView(model.selectedFundFlow.get, model.accounts),
        _ => PrettyLogger.info("Entering Accounts page")
      )
  case Toggle extends Page("/components/toggle", model => ToggleView(), _ => PrettyLogger.info("Entering Toggle page"))
}
