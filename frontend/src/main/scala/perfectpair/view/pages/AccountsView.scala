package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.{HttpHelper, PrettyLogger}
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
      case Msg.ReconcileAccount(accountId) =>
        (
          model.copy(reconcileStatuses = model.reconcileStatuses.updated(accountId, ReconcileStatus.InProgress)),
          HttpHelper.reconcileAccount(accountId)
        )
      case Msg.ReconcileResult(accountId, isSuccess) =>
        val status = if isSuccess then ReconcileStatus.Success else ReconcileStatus.Failed("Error")
        (model.copy(reconcileStatuses = model.reconcileStatuses.updated(accountId, status)), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    println("AccountView.apply fundFlow = " + model.fundFlow)
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        Buttons.linkButton("Add Account", AppMsg.NavigateToMaintainAccount(model.fundFlow, None)),
        Table.create(
          Seq("Name", "Balance", "", "", "", ""),
          model.accounts
            .sortBy(_.name)
            .map(account =>
              val accountId = account.id.getOrElse("")
              val status    = model.reconcileStatuses.getOrElse(accountId, ReconcileStatus.Idle)
              Seq(
                text(account.name),
                text(account.balance.toString),
                Buttons.textButton("Edit", AppMsg.AccountsMsg(Msg.SelectAccount(account))),
                if account.isRecon then Buttons.textButton("Recon Specs", AppMsg.NavigateToReconSpecs(account))
                else div(),
                if account.isRecon then
                  Buttons.textButton("Reconcile", AppMsg.AccountsMsg(Msg.ReconcileAccount(accountId)))
                else div(),
                statusBadge(status)
              )
            )
        )
      )
    )

  private def statusBadge(status: ReconcileStatus): Html[AppMsg] =
    status match
      case ReconcileStatus.Idle        => span()(text(""))
      case ReconcileStatus.InProgress  => span(cls := "text-xs text-blue-500")(text("Running…"))
      case ReconcileStatus.Success     => span(cls := "text-xs text-green-600 font-medium")(text("Done"))
      case ReconcileStatus.Failed(msg) => span(cls := "text-xs text-red-500")(text(s"Error: $msg"))

  enum ReconcileStatus:
    case Idle
    case InProgress
    case Success
    case Failed(msg: String)

  enum Msg:
    case SelectAccount(account: AccountPayload)
    case AccountsFromBackend(accounts: Seq[AccountPayload])
    case ReconcileAccount(accountId: String)
    case ReconcileResult(accountId: String, isSuccess: Boolean)

  case class Model(
    fundFlow:          FundFlowPayload              = FundFlowPayload(),
    accounts:          Seq[AccountPayload]          = Seq(),
    reconcileStatuses: Map[String, ReconcileStatus] = Map()
  )
