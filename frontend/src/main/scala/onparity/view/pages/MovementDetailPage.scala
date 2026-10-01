package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.MovementDetailPayload
import onparity.common.model.model.Money
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Card, DetailList, PageHeader}

object MovementDetailPage:

  case class State(detail: Option[MovementDetailPayload] = None)

  def apply(id: String): HtmlElement =
    val state   = Var(State())
    val loadBus = EventBus[Unit]()

    def load() =
      ApiClient.get[MovementDetailPayload](HttpPath.url(s"${HttpPath.movement}/$id"), SessionStore.token)

    PageHeader.detailPage(
      loadBus.events.flatMapSwitch(_ => load()).apiBind(d => state.update(_.copy(detail = Some(d)))),
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.backLink(Observer[Unit](_ => Nav.back())),
      child <-- state.signal.map {
        case State(None) => div()
        case State(Some(d)) =>
          div(
            PageHeader.title(s"Movement — ${d.referenceId}"),
            movementSection(d),
            propertiesSection("Properties", d.properties, last = false),
            propertiesSection("Metadata", d.metaData, last     = true)
          )
      }
    )

  private def movementSection(d: MovementDetailPayload): HtmlElement =
    div(
      cls := s"${Card.cls} p-4 mb-4",
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", "Movement"),
      DetailList(
        "ID"                -> DetailList.mono(d.id),
        "Reference ID"      -> DetailList.mono(d.referenceId),
        "Type"              -> d.movementType.map(span(_)).getOrElse(DetailList.empty),
        "Amount"            -> span(cls := "tabular-nums", d.amount.toString),
        "Movement Spec"     -> span(d.movementSpecName),
        "Primary Account"   -> accountView(d.inAccountName, d.inAccountBalance),
        "Secondary Account" -> accountView(d.outAccountName, d.outAccountBalance)
      )
    )

  // Account name, with its current balance as secondary detail rather than a parenthetical.
  private def accountView(name: String, balance: Money): HtmlElement =
    span(
      name,
      span(
        cls := "ml-2 text-xs text-md-on-surface-variant dark:text-gray-400 tabular-nums",
        s"Balance ${balance}"
      )
    )

  private def propertiesSection(title: String, props: Map[String, String], last: Boolean): HtmlElement =
    div(
      cls := (if last then s"${Card.cls} p-4" else s"${Card.cls} p-4 mb-4"),
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", title),
      if props.isEmpty then div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400", "None")
      else
        DetailList(
          props.toSeq.sortBy(_._1).map((k, v) => k -> (if v.isEmpty then DetailList.empty else span(cls := "break-all", v)))*
        )
    )
