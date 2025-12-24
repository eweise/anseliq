package com.eweise.perfectpair.view.pages

import com.eweise.perfectpair.common.model.{AccountTypePayload, FundFlowPayload}
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
            val reconAccount = ff.accounts.find(_.accountType == AccountTypePayload.Recon)
            Seq(
              Buttons.linkButton("Recon Details", Msg.GetAccountsForFundFlow(ff)),
              text(ff.name),
              text(s"account count = ${ff.accounts.length}"),
              text(s"recon Balance = ${reconAccount.fold(0)(_.balance)}")
            )
          )
        )
      )
    )
