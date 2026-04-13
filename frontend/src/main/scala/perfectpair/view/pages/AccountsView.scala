package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowResponse}
import perfectpair.model.Msg as AppMsg
import perfectpair.page.Page
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.components.{Buttons, Icons, Table}
import tyrian.{Cmd, Html}
import tyrian.Html.*
import zio.Task

object AccountsView:

  enum Msg:
    case GetReconAccounts
    case ReconAccountsFromBackend(accounts: Seq[AccountPayload])

  case class Model(
                    fundFlow: FundFlowResponse       = FundFlowResponse(),
                    accounts: Seq[AccountPayload]   = Seq()
  )

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.GetReconAccounts =>
        (model, HttpHelper.fetchReconAccounts |+| PrettyLogger.info("fetch recon accounts"))
      case Msg.ReconAccountsFromBackend(accounts) =>
        (model.copy(accounts = accounts), PrettyLogger.info("updated model with reconAccounts"))

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        span(onClick(AppMsg.NavigateTo(Page.FundFlows)))(text(s"${model.fundFlow.name}: Accounts View")),
        button(
          cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
          onClick(AppMsg.AccountsMsg(Msg.GetReconAccounts))
        )(Icons.user),
        Buttons.linkButton("Add Account", AppMsg.NavigateToMaintainAccount(model.fundFlow)),
        Table.create(
          Seq(
            "ID",
            "Name",
            "Balance"
          ),
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
