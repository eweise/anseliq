package perfectpair.view.pages

import perfectpair.common.model.AccountPayload
import perfectpair.model.Msg as AppMsg
import perfectpair.util.PayloadAction.*
import perfectpair.util.{HttpHelper, PayloadAction, PrettyLogger}
import perfectpair.view.components.{Buttons, Form}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object MaintainAccountView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.AccountFormUpdate(account) =>
        (model.copy(account = account), PrettyLogger.info(s"account form modified: $account"))
      case Msg.AccountAction(account, Create) =>
        (model, HttpHelper.createAccount(account) |+| PrettyLogger.info("creating account"))
      case Msg.AccountAction(account, Created) =>
        (model.copy(account = account), PrettyLogger.info("created account"))
      case Msg.AccountAction(account, Update) =>
        (model, HttpHelper.updateAccount(account) |+| PrettyLogger.info("updateing account"))
      case Msg.AccountAction(account, Updated) =>
        (model.copy(account = account), PrettyLogger.info("updated account"))

  def apply(model: Model): Html[AppMsg] =
    val account = model.account
    val action  = if model.account.id.isEmpty then Create else Update

    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Account")),
        Form.field[AppMsg](
          fieldID      = "name",
          labelName    = "Name",
          defaultValue = Some(account.name),
          onChangFn    = Some(value => AppMsg.MaintainAccountMsg(Msg.AccountFormUpdate(account.copy(name = value))))
        ),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", AppMsg.NavigateToAccounts(model.account.fundFlowId.toString)),
          Buttons.linkButton("Save", AppMsg.MaintainAccountMsg(Msg.AccountAction(account, action)))
        )
      )
    )

  enum Msg:
    case AccountFormUpdate(account: AccountPayload)
    case AccountAction(account: AccountPayload, action: PayloadAction)

  case class Model(
    account: AccountPayload
  )
