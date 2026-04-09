package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.Msg
import perfectpair.page.Page
import perfectpair.view.components.{Buttons, Table}
import tyrian.Html
import tyrian.Html.*

import java.time.format.DateTimeFormatter

object FundFlowsView:

  def apply(fundFlows: Seq[FundFlowPayload]): Html[Msg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "w-full max-w-4xl px-4")(
        div(cls := "flex justify-between items-center my-8")(
          h2(cls := "text-xl font-medium")(text("Fund Flows")),
          Buttons.linkButton("Create", Msg.NavigateTo(Page.MaintainFundFlow))
        ),
        Table.create(
          Seq("ID", "Name", "Created At", "Updated At", ""),
          fundFlows.map(ff =>
            Seq(
              text(ff.id),
              text(ff.name),
              text(ff.createdAt.get.toString),
              text(ff.updatedAt.get.toString),
              Buttons.textButton("Edit", Msg.SelectFundFlow(ff))
            )
          )
        )
      )
    )
