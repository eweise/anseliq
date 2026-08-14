package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.view.components.PageHeader

object ComingSoonPage:
  def apply(title: String): HtmlElement =
    div(
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center",
        PageHeader.title(title),
        p(cls := "text-md-on-surface-variant dark:text-gray-400 text-sm", "This area isn't built yet.")
      )
    )
