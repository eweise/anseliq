package perfectpair.view.components

import com.raquo.laminar.api.L.*

object PageHeader:
  def title(mods: Modifier[HtmlElement]*): HtmlElement =
    h2((cls := "text-2xl font-medium mb-6") +: mods*)
