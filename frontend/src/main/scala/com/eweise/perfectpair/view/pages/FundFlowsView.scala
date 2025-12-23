package com.eweise.perfectpair.view.pages

import com.eweise.perfectpair.common.model.{AccountType, FundFlowPayload}
import com.eweise.perfectpair.model.Msg
import com.eweise.perfectpair.view.components.{Buttons, Table}
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
          fundFlows.map(ff =>
            val reconAccount = ff.accounts.filter(_.accountType == AccountType.Recon).head
            Seq(
              Buttons.linkButton("Recon Details", Msg.NoOp),
              text(ff.name),
              text(reconAccount.name),
              text(reconAccount.balance.toString())
            )
          )
        )
      )
    )
