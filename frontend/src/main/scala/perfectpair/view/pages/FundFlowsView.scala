package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.Msg
import perfectpair.view.components.Table
import tyrian.Html
import tyrian.Html.*

object FundFlowsView:

  def apply(fundFlows: Seq[FundFlowPayload]): Html[Msg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        text("Fund Flows View"),
        Table.create(
          Seq(
            "ID",
            "Name",
            "Recon Account",
            "Balance"
          ),
          Seq()
        )
      )
    )
