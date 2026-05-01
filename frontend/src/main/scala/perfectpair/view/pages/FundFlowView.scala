package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.Msg as AppMsg
import tyrian.Html
import tyrian.Html.*

object FundFlowView:

  def apply(model: Model): Html[AppMsg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        h2(cls := "text-2xl font-medium mb-6")(
          text(model.fundFlow.name)
        )
      )
    )

  case class Model(fundFlow: FundFlowPayload = FundFlowPayload())
