package com.eweise.perfectpair.view.pages

import com.eweise.perfectpair.common.model.AdapterModel.AccountPayload
import com.eweise.perfectpair.model.Msg
import com.eweise.perfectpair.view.components.Icons
import tyrian.Html
import tyrian.Html.*

object FundFlowsView:

  def apply(accounts: Seq[AccountPayload]): Html[Msg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")(
        text("Accounts View"),
        button(
          cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
          onClick(Msg.GetReconAccounts)
        )(Icons.user),
        createTable(
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
  def createTable[M](headers: Seq[String], rows: Seq[Seq[String]]): Html[M] =
    div(cls := "relative overflow-x-auto bg-neutral-primary-soft shadow-xs rounded-base border border-default")(
      table(cls := "w-full text-sm text-left rtl:text-right text-body")(
        thead(cls := "text-sm text-body bg-neutral-secondary-soft border-b rounded-base border-default")(
          tr(
            headers.map(header => th(scope := "col", cls := "px-6 py-3 font-medium")(text(header)))*
          )
        ),
        tbody(
          rows.map( row =>
            tr(cls := "bg-neutral-primary border-b border-default")(
              row.map(col =>
                td(cls := "px-6 py-4")(text(col))
              )*
            )
          )*
        )
      )
    )
