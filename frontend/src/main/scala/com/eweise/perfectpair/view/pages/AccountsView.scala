package com.eweise.perfectpair.view.pages

import com.eweise.perfectpair.common.model.AccountPayload
import com.eweise.perfectpair.model.Msg
import com.eweise.perfectpair.view.components.{Icons, Table}
import tyrian.Html
import tyrian.Html.*

object AccountsView:

  def apply(accounts: Seq[AccountPayload]): Html[Msg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        text("Accounts View"),
        button(
          cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
          onClick(Msg.GetReconAccounts)
        )(Icons.user),
        Table.create(
          Seq(
            "ID",
            "Name",
            "Account Type",
            "Fund Flow ID",
            "Balance"
          ),
          accounts.map(account => Seq(
            account.id,
            account.name,
            account.accountType,
            account.fundFlowId.toString,
            account.balance.toString
          ))
        ),
      )
    )
