package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.*
import onparity.http.ApiClient
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{PageHeader, Table}

object FileInventoryPage:

  def apply(): HtmlElement =
    val state    = Var(State())
    val fetchBus = EventBus[Unit]()

    PageHeader.page(
      EventStream
        .merge(EventStream.fromValue((), emitOnce = true), fetchBus.events)
        .flatMapSwitch { _ =>
          ApiClient.get[Seq[FileInventoryPayload]](HttpPath.url(HttpPath.fileInventory), SessionStore.token)
        }
        .apiBind(entries => state.update(_.copy(entries = entries))),
      PageHeader.titleRow(
        cls := "flex items-center justify-between",
        PageHeader.title("File Inventory"),
        PageHeader.refreshButton(Observer[Unit](_ => fetchBus.emit(())))
      ),
      div(
        cls := "w-full px-8",
        child <-- state.signal.map { s =>
          val headers = Seq(
            "File Feed",
            "Source System",
            "File Name",
            "File Size (bytes)",
            "Last Modified At",
            "Status",
            "Last Processed At",
            "Last Processed Line",
            "Updated At"
          )
          val rows = s.entries.map { entry =>
            Seq(
              span(entry.fileFeedName),
              span(entry.sourceSystem),
              span(entry.fileName),
              span(entry.fileSizeBytes.toString),
              span(ViewHelper.formatDateTime(entry.lastModifiedAt)),
              span(entry.status),
              span(entry.lastProcessedAt.map(ViewHelper.formatDateTime).getOrElse("-")),
              span(entry.lastProcessedLine.map(_.toString).getOrElse("-")),
              span(entry.updatedAt.map(ViewHelper.formatDateTime).getOrElse("-"))
            )
          }
          Table.create(headers, rows)
        }
      )
    )

  case class State(entries: Seq[FileInventoryPayload] = Seq.empty)
