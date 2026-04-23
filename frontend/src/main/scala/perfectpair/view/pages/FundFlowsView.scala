package perfectpair.view.pages

import perfectpair.common.model.{DisplayName, FundFlowPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.page.Page
import perfectpair.util.PrettyLogger
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.Task

object FundFlowsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FundFlowsFromBackend(ff) =>
        (model.copy(fundFlows = ff), PrettyLogger.info("updated model with fund flows"))
      case Msg.SelectFundFlow(ff) =>
        (model, Cmd.Emit(AppMsg.NavigateToMaintainFundFlow(ff)))
      case Msg.GetAccountsForFundFlow(ff) =>
        (model, Cmd.Emit(AppMsg.NavigateToAccounts(ff)))
      case Msg.GetMovementSpecsForFundFlow(ff) =>
        (model, Cmd.Emit(AppMsg.NavigateToMovementSpecs(ff)))

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col mb-8 pt-20")(
      div(cls := "w-full px-8")(
        div(cls := "flex justify-between items-center my-8")(
          h2(cls := "text-xl font-medium")(text("Fund Flows")),
          Buttons.linkButton("Create", AppMsg.NavigateTo(Page.MaintainFundFlow))
        ),
        Table.create(
          Seq(
            "Name",
            "Created At",
            "Updated At",
            "",
            ""
          ),
          model.fundFlows
            .sortBy(_.name)
            .map(ff =>
              Seq(
                text(ff.name),
                text(ViewHelper.formatDateTime(ff.createdAt.get)),
                text(ViewHelper.formatDateTime(ff.updatedAt.get)),
                Buttons.textButton(DisplayName.accounts, AppMsg.FundFlowsMsg(Msg.GetAccountsForFundFlow(ff))),
                Buttons.textButton(DisplayName.movementSpecs, AppMsg.FundFlowsMsg(Msg.GetMovementSpecsForFundFlow(ff))),
                Buttons.textButton("Edit", AppMsg.FundFlowsMsg(Msg.SelectFundFlow(ff)))
              )
            )
        )
      )
    )

  enum Msg:
    case FundFlowsFromBackend(fundFlows: Seq[FundFlowPayload])
    case SelectFundFlow(fundFlow: FundFlowPayload)
    case GetAccountsForFundFlow(fundFlow: FundFlowPayload)
    case GetMovementSpecsForFundFlow(fundFlow: FundFlowPayload)

  case class Model(fundFlows: Seq[FundFlowPayload] = Seq())
