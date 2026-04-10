package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.{Msg as AppMsg}
import perfectpair.page.Page
import perfectpair.util.PrettyLogger
import perfectpair.view.components.{Buttons, Table}
import tyrian.Cmd
import tyrian.Html
import tyrian.Html.*
import zio.Task
import zio.interop.catz.*

import java.time.format.DateTimeFormatter

object FundFlowsView:

  enum Msg:
    case FundFlowsFromBackend(fundFlows: Seq[FundFlowPayload])
    case SelectFundFlow(fundFlow: FundFlowPayload)
    case GetAccountsForFundFlow(fundFlow: FundFlowPayload)

  case class Model(fundFlows: Seq[FundFlowPayload] = Seq())

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FundFlowsFromBackend(ff) =>
        (model.copy(fundFlows = ff), PrettyLogger.info("updated model with fund flows"))
      case Msg.SelectFundFlow(ff) =>
        (model, Cmd.Emit(AppMsg.NavigateToMaintainFundFlow(ff)))
      case Msg.GetAccountsForFundFlow(ff) =>
        (model, Cmd.Emit(AppMsg.NavigateToAccounts(ff)))

  def apply(fundFlows: Seq[FundFlowPayload]): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "w-full max-w-4xl px-4")(
        div(cls := "flex justify-between items-center my-8")(
          h2(cls := "text-xl font-medium")(text("Fund Flows")),
          Buttons.linkButton("Create", AppMsg.NavigateTo(Page.MaintainFundFlow))
        ),
        Table.create(
          Seq("ID", "Name", "Created At", "Updated At", ""),
          fundFlows.map(ff =>
            Seq(
              text(ff.id),
              text(ff.name),
              text(ff.createdAt.get.toString),
              text(ff.updatedAt.get.toString),
              Buttons.textButton("Edit", AppMsg.SelectFundFlow(ff))
            )
          )
        )
      )
    )
