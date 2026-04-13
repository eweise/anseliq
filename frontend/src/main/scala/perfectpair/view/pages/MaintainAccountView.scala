package perfectpair.view.pages

import perfectpair.common.model.{AccountPayload, FundFlowResponse}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.components.{Buttons, Form}
import tyrian.Cmd
import tyrian.Html
import tyrian.Html.*
import zio.Task
import zio.interop.catz.*

import java.util.UUID

object MaintainAccountView:

  enum Msg:
    case AccountFormUpdate(account: AccountPayload)
    case CreateAccount(account: AccountPayload)
    case AccountCreated(account: AccountPayload)

  case class Model(
    fundFlow: FundFlowResponse = FundFlowResponse(),
    account:  AccountPayload   = AccountPayload(
      id         = "",
      name       = "",
      fundFlowId = UUID.fromString("00000000-0000-0000-0000-000000000000"),
      balance    = BigDecimal(0)
    )
  )

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.AccountFormUpdate(account) =>
        (model.copy(account = account), PrettyLogger.info(s"account form modified: $account"))
      case Msg.CreateAccount(account) =>
        if account.name.isBlank then (model, Cmd.emit(AppMsg.Error("Name is required")))
        else (model, HttpHelper.createAccount(account) |+| PrettyLogger.info("creating account"))
      case Msg.AccountCreated(account) =>
        (model.copy(account = account), PrettyLogger.info("created account"))

  def apply(model: Model): Html[AppMsg] =
    val account = model.account
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
          Buttons.textButton("Cancel", AppMsg.NavigateToAccounts(model.fundFlow)),
          Buttons.linkButton("Save", AppMsg.MaintainAccountMsg(Msg.CreateAccount(account)))
        )
      )
    )
