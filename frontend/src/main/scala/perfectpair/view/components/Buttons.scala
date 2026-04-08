package perfectpair.view.components

import tyrian.Html
import tyrian.Html.*

object Buttons:
  def linkButton[M](title: String, msg: M): Html[M] =
    button(
      cls := "bg-md-primary text-md-on-primary rounded-full px-6 py-2.5 text-sm font-medium tracking-wide shadow-sm hover:bg-md-primary-hover transition-colors",
      tpe := "button",
      onClick(msg)
    )(text(title))

  def textButton[M](title: String, msg: M): Html[M] =
    button(
      cls := "text-md-primary rounded-full px-6 py-2.5 text-sm font-medium tracking-wide hover:bg-md-primary-container transition-colors",
      tpe := "button",
      onClick(msg)
    )(text(title))
