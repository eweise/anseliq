package perfectpair.view.components

import tyrian.Html
import tyrian.Html.*

object Form:
  def field[M](
    fieldID: String,
    labelName: String,
    onChangFn: String => M,
    maybeErrorMsg: Option[String] = None
  ): Html[M] =
    val hasError  = maybeErrorMsg.isDefined
    val borderCls = if hasError then "border-2 border-md-error"
                    else "border border-md-outline focus-within:border-2 focus-within:border-md-primary"
    val labelCls  = if hasError then "text-md-error" else "text-md-on-surface-variant"
    div(cls := s"relative mb-5 $borderCls rounded")(
      label(
        cls       := s"absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide $labelCls",
        attribute("for", fieldID)
      )(text(labelName)),
      input(
        id  := fieldID,
        cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onChange(onChangFn),
        required
      ),
      maybeErrorMsg
        .map(errMsg =>
          p(cls := "absolute -bottom-5 left-0 px-4 text-xs text-md-error")(text(errMsg))
        )
        .getOrElse(div()())
    )
