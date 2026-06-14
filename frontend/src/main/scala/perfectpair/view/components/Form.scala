package perfectpair.view.components

import com.raquo.laminar.api.L.*

object Form:

  def field(
    id:       String,
    lbl:      String,
    value:    String,
    inputFn:  String => Unit = _ => (),
    enabled:  Boolean = true
  ): HtmlElement =
    div(cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
      label(
        forId := id,
        cls   := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant",
        lbl
      ),
      input(
        idAttr       := id,
        defaultValue := value,
        cls          := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onInput.mapToValue --> Observer[String](inputFn),
        if !enabled then disabled := true else emptyMod
      )
    )

  def selectField(
    id:       String,
    lbl:      String,
    options:  Seq[(String, String)],
    selected: String,
    changeFn: String => Unit
  ): HtmlElement =
    div(cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
      label(
        forId := id,
        cls   := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant",
        lbl
      ),
      select(
        idAttr := id,
        cls    := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onMountCallback { ctx => ctx.thisNode.ref.value = selected },
        onChange.mapToValue --> Observer[String](changeFn)
      ).amend(options.map { (v, optLbl) => option(value := v, optLbl) }*)
    )
