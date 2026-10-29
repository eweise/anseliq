package anseliq.view.components

import com.raquo.laminar.api.L.*
import anseliq.common.model.LedgerPayload
import anseliq.routing.Nav

object PageHeader:
  // The outer chrome every full-width content page shares (top padding under the fixed nav,
  // bottom margin). Individual sections inside — the title row, filters, tabs, tables — are still
  // the page's own children; this only pins the shared frame so every page starts from the same
  // baseline instead of hand-copying (and slowly drifting from) the same class string.
  def page(mods: Modifier[HtmlElement]*): HtmlElement =
    div((cls := "flex flex-col mb-8 pt-20") +: mods*)

  // Centered narrower variant of `page`, for detail-style pages (a single record's full detail,
  // read top-to-bottom) rather than a full-width list/table. `relative` gives backLink (an
  // `absolute` child, see below) a positioning anchor.
  def detailPage(mods: Modifier[HtmlElement]*): HtmlElement =
    div(
      cls := "flex flex-col items-center mb-8 pt-20",
      div((cls := "relative my-8 text-left w-full max-w-4xl") +: mods*)
    )

  // The one slot every page's title (and optional backLink above it) should occupy: a fixed 32px
  // gap below `page`'s top padding, full-width gutter — so the title lands in the exact same spot
  // regardless of what a given page renders below it. Previously about half the pages omitted this
  // margin entirely (title flush against the top padding) while the other half added it by hand,
  // inconsistently (sometimes on this block, sometimes on a title+action-button row one level in).
  // `relative` gives backLink (an `absolute` child, see below) a positioning anchor.
  def titleRow(mods: Modifier[HtmlElement]*): HtmlElement =
    div((cls := "relative my-8 w-full px-8") +: mods*)

  def title(mods: Modifier[HtmlElement]*): HtmlElement =
    h2((cls := "text-2xl font-medium mb-6 text-gray-900 dark:text-white") +: mods*)

  // The one Back slot every page that has one should use: top-left, directly above the title, as
  // a low-emphasis text link rather than a heavy primary-color pill competing with real actions
  // like Save. Previously scattered across pages (below the title, right-aligned at the bottom of
  // a card, styled as Buttons.linkButton or textButton depending on the page) — centralized here
  // so "Back" always looks and sits the same way everywhere.
  //
  // `absolute` + only `top` set (no `left`/`right`) floats this up into the blank gap between the
  // page's top padding and the title, without adding to titleRow/detailPage's own flow height —
  // otherwise a page with a Back link would end up with its title sitting lower than a page
  // without one. Leaving `left` unset resolves to this button's normal-flow ("static") horizontal
  // position, which already matches the title's left edge in both titleRow (px-8 padding) and
  // detailPage (no padding) without needing to hardcode either one here.
  def backLink(onBack: Observer[Unit]): HtmlElement =
    button(
      typ := "button",
      cls := "absolute -top-6 flex items-center gap-1 -ml-1 px-1 text-sm text-md-on-surface-variant dark:text-gray-400 hover:text-md-primary dark:hover:text-md-primary transition-colors",
      onClick.mapToUnit --> onBack,
      span(cls := "text-base leading-none", "←"),
      span("Back")
    )

  // Breadcrumb trail for a detail page, in the same slot as backLink (which it replaces): it says
  // where the record lives — "Ledgers › Scenario1 › Reconciliations › 3b94d94b" — rather than
  // depending on how the user happened to arrive, which is all a history-based Back can offer.
  // Every crumb but the last is a link; the last names the current page.
  def breadcrumb(crumbs: (String, Option[Observer[Unit]])*): HtmlElement =
    val linkCls = "hover:text-md-primary dark:hover:text-md-primary transition-colors cursor-pointer"
    navTag(
      aria.label := "Breadcrumb",
      cls        := "absolute -top-6 flex flex-wrap items-center gap-1 text-sm text-md-on-surface-variant dark:text-gray-400",
      crumbs.zipWithIndex.flatMap { case ((label, onOpen), i) =>
        val sep = if i == 0 then None else Some(span(cls := "text-gray-400 dark:text-gray-500", "›"))
        val item = onOpen match
          case Some(obs) => button(typ := "button", cls := linkCls, onClick.mapToUnit --> obs, label)
          case None      => span(cls := "text-md-on-surface dark:text-gray-200", label)
        sep.toSeq :+ item
      }
    )

  // "Ledgers › <ledger> › …trail" for a page that lives inside a ledger. Each ledger-level crumb
  // after the ledger's own name (e.g. "Reconciliations") is given as the tab it opens. Without a
  // known ledger (e.g. the page was opened directly) the trail starts at Ledgers.
  def ledgerBreadcrumb(
    ff:    Option[LedgerPayload],
    tab:   Option[(String, String)], // (label, tab id) — the ledger tab this page belongs under
    trail: (String, Option[Observer[Unit]])*
  ): HtmlElement =
    val ledgers = "Ledgers" -> Some(Observer[Unit](_ => Nav.toLedgers()))
    val ledgerCrumbs = ff.toSeq.flatMap { f =>
      (f.name -> Some(Observer[Unit](_ => Nav.toLedger(f, "Overview", None)))) +:
        tab.toSeq.map((label, id) => label -> Some(Observer[Unit](_ => Nav.toLedger(f, id, None))))
    }
    breadcrumb((ledgers +: ledgerCrumbs) ++ trail*)

  // The one Refresh slot a page whose data can change while the user is looking at it (Jobs,
  // Events, File Inventory today) should use: right-aligned on the title row itself, next to the
  // title — the standard spot for a page-level refresh action. Previously each page placed it
  // differently (alone on its own right-aligned row, or inline after a filter dropdown) and styled
  // it as a solid green circle, a level of color saturation nothing else on these pages earns.
  // Ghost icon-only styling instead, matching the dark-mode toggle in the top bar.
  def refreshButton(onRefresh: Observer[Unit]): HtmlElement =
    button(
      typ := "button",
      cls := "flex items-center justify-center w-9 h-9 rounded-full text-gray-600 dark:text-gray-300 hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark transition-colors",
      onClick.mapToUnit --> onRefresh,
      Icons.refresh
    )
