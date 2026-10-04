package anseliq.view.components

import com.raquo.laminar.api.L.*

object Buttons:
  def linkButton(label: String, obs: Observer[Unit]): HtmlElement =
    button(
      cls := "bg-md-primary text-md-on-primary rounded-full px-6 py-2.5 text-sm font-medium tracking-wide shadow-sm hover:bg-md-primary-hover transition-colors",
      typ := "button",
      onClick.mapToUnit --> obs,
      label
    )

  def textButton(label: String, obs: Observer[Unit]): HtmlElement =
    button(
      cls := "text-md-primary rounded-full px-6 py-2.5 text-sm font-medium tracking-wide hover:bg-md-primary-container transition-colors",
      typ := "button",
      onClick.mapToUnit --> obs,
      label
    )
