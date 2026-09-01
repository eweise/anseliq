package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.MovementDetailPayload
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Card, PageHeader}

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
      div(
        cls := "text-sm space-y-1 text-md-on-surface dark:text-gray-200",
        div(s"ID: ${d.id}"),
        div(s"Reference ID: ${d.referenceId}"),
        div(s"Type: ${d.movementType.getOrElse("Unclassified")}"),
        div(s"Amount: ${d.amount}"),
        div(s"Movement Spec: ${d.movementSpecName}"),
        div(s"Out Account: ${d.outAccountName} (Balance: ${d.outAccountBalance})"),
        div(s"In Account: ${d.inAccountName} (Balance: ${d.inAccountBalance})")
      )
    )

  private def propertiesSection(title: String, props: Map[String, String], last: Boolean): HtmlElement =
    div(
      cls := (if last then s"${Card.cls} p-4" else s"${Card.cls} p-4 mb-4"),
      h3(cls := "font-medium mb-3 text-md-on-surface dark:text-white", title),
      if props.isEmpty then div(cls := "text-sm text-md-on-surface-variant dark:text-gray-400", "None")
      else
        table(
          cls := "text-sm w-full",
          tbody(
            props.toSeq.sortBy(_._1).map { (k, v) =>
              tr(
                cls := "border-b border-md-outline dark:border-gray-700 last:border-b-0",
                td(cls := "py-1 pr-4 font-medium text-md-on-surface-variant dark:text-gray-400 align-top", k),
                td(cls := "py-1 text-md-on-surface dark:text-gray-200 break-all", v)
              )
            }*
          )
        )
    )
