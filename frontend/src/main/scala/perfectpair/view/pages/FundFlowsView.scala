package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.{Msg as AppMsg}
import perfectpair.page.Page
import perfectpair.util.PrettyLogger
import perfectpair.view.components.Buttons
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

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col mb-8 pt-20")(
      div(cls := "w-full px-8")(
        div(cls := "flex justify-between items-center my-8")(
          h2(cls := "text-xl font-medium")(text("Fund Flows")),
          Buttons.linkButton("Create", AppMsg.NavigateTo(Page.MaintainFundFlow))
        ),
        div(cls := "flex items-center px-6 py-2 text-sm font-medium text-gray-500")(
          span(cls := "w-48")(text("ID")),
          span(cls := "flex-1")(text("Name")),
          span(cls := "w-56")(text("Created At")),
          span(cls := "w-56")(text("Updated At")),
          span(cls := "w-20")()
        ),
        div(cls := "flex flex-col gap-2")(
          model.fundFlows.map(ff =>
            div(cls := "flex items-center gap-3")(
              div(cls := "flex-1 flex items-center bg-white rounded-lg shadow-sm border border-gray-200 px-6 py-4 text-sm")(
                span(cls := "w-48 text-gray-500")(text(ff.id)),
                span(cls := "flex-1 font-medium")(text(ff.name)),
                span(cls := "w-56 text-gray-500")(text(ff.createdAt.get.toString)),
                span(cls := "w-56 text-gray-500")(text(ff.updatedAt.get.toString))
              ),
              Buttons.textButton("Accounts", AppMsg.FundFlowsMsg(Msg.GetAccountsForFundFlow(ff))),
              Buttons.textButton("Edit", AppMsg.FundFlowsMsg(Msg.SelectFundFlow(ff)))
            )
          )*
        )
      )
    )
