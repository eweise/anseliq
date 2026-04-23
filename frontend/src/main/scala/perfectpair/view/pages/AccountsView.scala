package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.PrettyLogger
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object AccountsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.SelectAccount(account) =>
        (model, Cmd.Emit(AppMsg.NavigateToMaintainAccount(model.fundFlow, Some(account))))
      case Msg.AccountsFromBackend(accounts) =>
        println("AccountsView Msg.AccountsFromBackend(accounts) " + accounts)
        (model.copy(accounts = accounts), PrettyLogger.info("updated model with reconAccounts"))

  def apply(model: Model): Html[AppMsg] =
    println("AccountView.apply fundFlow = " + model.fundFlow)
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Buttons.linkButton("Add Account", AppMsg.NavigateToMaintainAccount(model.fundFlow, None)),
        Table.create(
          Seq(
            "Name",
            "Balance",
            "",
            ""
          ),
          model.accounts
            .sortBy(_.name)
            .map(account =>
              Seq(
                text(account.name),
                text(account.balance.toString),
                Buttons.textButton("Edit", AppMsg.AccountsMsg(Msg.SelectAccount(account))),
                Buttons.textButton("Recon Specs", AppMsg.NavigateToReconSpecs(account))
              )
            )
        )
      )
    )

  enum Msg:
    case SelectAccount(account: AccountPayload)
    case AccountsFromBackend(accounts: Seq[AccountPayload])

  case class Model(
    fundFlow: FundFlowPayload     = FundFlowPayload(),
    accounts: Seq[AccountPayload] = Seq()
  )
