package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.{Msg as AppMsg}
import perfectpair.page.Page
import perfectpair.util.{HttpHelper, PrettyLogger}
import perfectpair.view.components.{Buttons, Form}
import tyrian.Cmd
import tyrian.Html
import tyrian.Html.*
import zio.Task
import zio.interop.catz.*

object MaintainFundFlowView:

  enum Msg:
    case FundFlowFormUpdate(fundFlow: FundFlowPayload)
    case CreateFundFlow(fundFlow: FundFlowPayload)
    case FundFlowCreated(fundFlow: FundFlowPayload)

  case class Model(fundFlow: FundFlowPayload = FundFlowPayload())

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FundFlowFormUpdate(ff) =>
        (model.copy(fundFlow = ff), PrettyLogger.info(s"fund flow form modified: $ff"))
      case Msg.CreateFundFlow(ff) =>
        if ff.name.isBlank then (model, Cmd.emit(AppMsg.Error("Name is required")))
        else (model, HttpHelper.createFundFlow(ff) |+| PrettyLogger.info("creating fund flow"))
      case Msg.FundFlowCreated(ff) =>
        (model.copy(fundFlow = ff), PrettyLogger.info("created fund flow"))

  def apply(model: Model): Html[AppMsg] =
    val ff = model.fundFlow
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Fund Flow")),
        Form.field[AppMsg](
          fieldID      = "name",
          labelName    = "Name",
          defaultValue = Some(ff.name),
          onChangFn    = Some(value => AppMsg.MaintainFundFlowMsg(Msg.FundFlowFormUpdate(ff.copy(name = value))))
        ),
        ff.createdAt
          .map(createdAt =>
            Form.field[AppMsg](
              fieldID      = "created_at",
              labelName    = "CreatedAt",
              defaultValue = Some(createdAt),
              enabled      = false
            )
          )
          .getOrElse(div()()),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", AppMsg.NavigateTo(Page.FundFlows)),
          Buttons.linkButton("Save", AppMsg.MaintainFundFlowMsg(Msg.CreateFundFlow(ff)))
        )
      )
    )
