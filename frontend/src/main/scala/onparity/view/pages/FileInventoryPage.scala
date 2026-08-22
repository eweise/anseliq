package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.*
import onparity.http.ApiClient
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Icons, PageHeader, Table}

object FileInventoryPage:

  def apply(): HtmlElement =
    val state    = Var(State())
    val fetchBus = EventBus[Unit]()

    div(
      EventStream
        .merge(EventStream.fromValue((), emitOnce = true), fetchBus.events)
        .flatMapSwitch { _ =>
          ApiClient.get[Seq[FileInventoryPayload]](HttpPath.url(HttpPath.fileInventory), SessionStore.token)
        } --> {
        case Right(entries) => state.update(_.copy(entries = entries))
        case Left(e)        => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20 px-8 w-full",
      div(
        cls := "w-full max-w-5xl",
        PageHeader.title("File Inventory"),
        div(
          cls := "mb-6 flex items-end justify-end gap-3",
          button(
            cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-10 h-10 rounded-full hover:bg-green-500",
            typ := "button",
            onClick --> (_ => fetchBus.emit(())),
            Icons.refresh
          )
        ),
        child <-- state.signal.map { s =>
          val headers = Seq(
            "Movement Spec",
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
              span(entry.movementSpecName),
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
