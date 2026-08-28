package onparity.view.components

import com.raquo.laminar.api.L.*

object Form:

  def field(
    id:        String,
    lbl:       String,
    value:     String,
    inputFn:   String => Unit      = _ => (),
    enabled:   Boolean             = true,
    updates:   EventStream[String] = EventStream.empty,
    inputType: String              = "text"
  ): HtmlElement =
    div(
      cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
      label(
        forId := id,
        cls   := "absolute -top-2.5 left-3 bg-white dark:bg-gray-800 px-1 text-xs font-medium tracking-wide text-md-on-surface-variant dark:text-gray-400",
        lbl
      ),
      input(
        idAttr       := id,
        typ          := inputType,
        defaultValue := value,
        cls          := "w-full px-4 py-3.5 bg-transparent text-md-on-surface dark:text-white text-base focus:outline-none rounded",
        onInput.mapToValue --> Observer[String](inputFn),
        if !enabled then disabled := true else emptyMod,
        inContext(el => updates --> Observer[String](v => el.ref.value = v))
      )
    )

  def selectField(
    id:       String,
    lbl:      String,
    options:  Seq[(String, String)],
    selected: String,
    changeFn: String => Unit,
    updates:  EventStream[String] = EventStream.empty
  ): HtmlElement =
    div(
      cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
      label(
        forId := id,
        cls   := "absolute -top-2.5 left-3 bg-white dark:bg-gray-800 px-1 text-xs font-medium tracking-wide text-md-on-surface-variant dark:text-gray-400",
        lbl
      ),
      select(
        idAttr := id,
        cls    := "w-full px-4 py-3.5 bg-transparent text-md-on-surface dark:text-white text-base focus:outline-none rounded",
        onMountCallback(ctx => ctx.thisNode.ref.value = selected),
        onChange.mapToValue --> Observer[String](changeFn),
        inContext(el => updates --> Observer[String](v => el.ref.value = v))
      ).amend(options.map((v, optLbl) => option(value := v, optLbl))*)
    )

  // Shared styling for compact table-cell config widgets (e.g. DataSourceConfigWidget's per-row
  // CSV/BAI/JSON tables) — smaller and border-boxed rather than field/selectField's underlined
  // full-width style, but every text/background/border class still needs an explicit dark:
  // variant, the same as field/selectField above: this app's custom md-* Tailwind colors don't
  // automatically adapt to dark mode on their own, so each one has to be paired by hand.
  val tableCls           = "w-full text-xs border-collapse text-md-on-surface dark:text-gray-200"
  val tableContainerCls  = "border border-md-outline dark:border-gray-700 rounded overflow-hidden"
  val tableHeadCls       = "bg-md-surface-variant dark:bg-md-surface-alt-dark"
  val tableHeaderCellCls = "text-md-on-surface-variant dark:text-gray-400 font-medium"
  val tableRowCls        = "border-t border-md-outline-variant dark:border-gray-700"
  val sectionLabelCls    = "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant dark:text-gray-400"
  val mutedTextCls       = "text-md-on-surface-variant dark:text-gray-400"
  val tableInputCls =
    "w-full border border-md-outline dark:border-gray-700 rounded px-2 py-1 text-xs bg-transparent text-md-on-surface dark:text-white focus:outline-none focus:border-md-primary"

  // The repeated shape behind every cell in those tables: same compact styling every time,
  // differing only in input type, placeholderText, current value, the change handler, and — for
  // rows in a growable list reused via splitByIndex — updates, so the DOM node's value stays in
  // sync when a row is recycled for a different index across state updates (same rebind idea as
  // field's own `updates` param above; omit it for a fixed-identity row, e.g. one per BAIField,
  // where that reuse never happens). inContext has to stay inside this input(...) call, not be
  // built by the caller and passed in — pulled out to a standalone value it loses the type
  // information tying `el` to an actual <input> element, and el.ref.value stops resolving.
  def tableInput(
    inputType:       String,
    value:           String,
    onValueChange:   String => Unit,
    placeholderText: String              = "",
    updates:         EventStream[String] = EventStream.empty
  ): HtmlElement =
    input(
      typ          := inputType,
      cls          := tableInputCls,
      placeholder  := placeholderText,
      defaultValue := value,
      onInput.mapToValue --> Observer[String](onValueChange),
      inContext(el => updates --> Observer[String](v => el.ref.value = v))
    )
