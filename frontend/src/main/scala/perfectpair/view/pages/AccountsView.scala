package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload}
import perfectpair.model.{Msg as AppMsg}
import perfectpair.page.Page
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.components.{Icons, Table}
import tyrian.Cmd
import tyrian.Html
import tyrian.Html.*
import zio.Task
import zio.interop.catz.*

object AccountsView:

  enum Msg:
    case GetReconAccounts
    case ReconAccountsFromBackend(accounts: Seq[AccountPayload])

  case class Model(
    fundFlow: FundFlowPayload       = FundFlowPayload(),
    accounts: Seq[AccountPayload]   = Seq()
  )

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.GetReconAccounts =>
        (model, HttpHelper.fetchReconAccounts |+| PrettyLogger.info("fetch recon accounts"))
      case Msg.ReconAccountsFromBackend(accounts) =>
        (model.copy(accounts = accounts), PrettyLogger.info("updated model with reconAccounts"))

  def apply(fundFlowPayload: FundFlowPayload, accounts: Seq[AccountPayload]): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        span(onClick(AppMsg.NavigateTo(Page.FundFlows)))(text(s"${fundFlowPayload.name}: Accounts View")),
        button(
          cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
          onClick(AppMsg.GetReconAccounts)
        )(Icons.user),
        Table.create(
          Seq(
            "ID",
            "Name",
            "Balance"
          ),
          accounts.map(account =>
            Seq(
              text(account.id),
              text(account.name),
              text(account.balance.toString)
            )
          )
        )
      )
    )
