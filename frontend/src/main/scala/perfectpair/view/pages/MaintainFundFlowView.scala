package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.Msg as AppMsg
import perfectpair.page.Page
import perfectpair.util.PayloadAction.*
import perfectpair.util.{HttpHelper, PayloadAction, PrettyLogger}
import perfectpair.view.components.{Buttons, Form}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object MaintainFundFlowView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FundFlowFieldChanged(ff) =>
        (model.copy(fundFlow = ff), PrettyLogger.info(s"fund flow modified: $ff"))
      case Msg.MaintainFundFlow(ff, Update) =>
        if ff.name.isBlank then (model, Cmd.emit(AppMsg.Error("Name is required")))
        else (model, HttpHelper.updateFundFlow(ff) |+| PrettyLogger.info("updating fund flow"))
      case Msg.MaintainFundFlow(ff, Updated) =>
        (
          model.copy(fundFlow = ff),
          PrettyLogger.info(s"fund flow updated: $ff") |+| Cmd.emit(AppMsg.NavigateTo(Page.FundFlows))
        )
      case Msg.MaintainFundFlow(ff, Create) =>
        if ff.name.isBlank then (model, Cmd.emit(AppMsg.Error("Name is required")))
        else (model, HttpHelper.createFundFlow(ff) |+| PrettyLogger.info("creating fund flow"))
      case Msg.MaintainFundFlow(ff, Created) =>
        (
          model.copy(fundFlow = ff),
          PrettyLogger.info("created fund flow") |+| Cmd.emit(AppMsg.NavigateTo(Page.FundFlows))
        )

  def apply(model: Model): Html[AppMsg] =
    val ff     = model.fundFlow
    val action = if ff.id.isEmpty then Create else Update

    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Fund Flow")),
        Form.field[AppMsg](
          fieldID      = "name",
          labelName    = "Name",
          defaultValue = Some(ff.name),
          onChangFn    = Some(value => AppMsg.MaintainFundFlowMsg(Msg.FundFlowFieldChanged(ff.copy(name = value))))
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
          Buttons.linkButton("Save", AppMsg.MaintainFundFlowMsg(Msg.MaintainFundFlow(fundFlow = ff, action = action)))
        )
      )
    )

  enum Msg:
    case MaintainFundFlow(fundFlow: FundFlowPayload, action: PayloadAction)
    case FundFlowFieldChanged(fundFlow: FundFlowPayload)

  case class Model(fundFlow: FundFlowPayload = FundFlowPayload())
