package perfectpair.view.pages

import perfectpair.common.model.FundFlowPayload
import perfectpair.model.Msg
import perfectpair.page.Page
import perfectpair.view.components.{Buttons, Form}
import tyrian.Html
import tyrian.Html.*

object MaintainFundFlowView:
  def apply(ff: FundFlowPayload): Html[Msg] =
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-md p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-7")(text("Fund Flow")),
        Form.field[Msg](
          fieldID      = "name",
          labelName    = "Name",
          defaultValue = Some(ff.name),
          onChangFn    = Some(value => Msg.FundFlowFormUpdate(ff.copy(name = value)))
        ),
        ff.createdAt
          .map(createdAt =>
            Form.field[Msg](
              fieldID      = "created_at",
              labelName    = "CreatedAt",
              defaultValue = Some(createdAt),
              enabled      = false
            )
          )
          .getOrElse(div()()),
        div(cls := "flex justify-end gap-2 mt-6")(
          Buttons.textButton("Cancel", Msg.NavigateTo(Page.FundFlows)),
          Buttons.linkButton("Save", Msg.CreateFundFlow(ff))
        )
      )
    )
