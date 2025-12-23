package com.eweise.perfectpair.view.components

import tyrian.Html
import tyrian.Html.*

object Buttons:
  def linkButton[M](title: String, msg: M): Html[M] =
    button(
      cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-12 h-12 rounded-full hover:animate-spin mx-6 ml-auto transition-all duration-300 hover:bg-green-500 hover:text-white",
      onClick(msg)
    )(text(title))
