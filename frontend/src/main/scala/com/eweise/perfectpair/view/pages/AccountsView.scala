package com.eweise.perfectpair.view.pages

import com.eweise.perfectpair.model.Msg
import tyrian.Html
import tyrian.Html.*

object AccountsView:

  def apply(): Html[Msg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      div(cls := "my-8 text-center")("Accounts View")
