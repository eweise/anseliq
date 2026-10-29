package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.http.ApiClient
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.ViewHelper
import anseliq.routing.Nav
import anseliq.view.components.{Buttons, PageHeader, Table}

object FileInventoryPage:

  def apply(): HtmlElement =
    val state    = Var(State())
    val fetchBus = EventBus[Unit]()

    PageHeader.page(
      EventStream
        .merge(EventStream.fromValue((), emitOnce = true), fetchBus.events)
        .flatMapSwitch { _ =>
          ApiClient.get[Seq[FileInventoryPayload]](HttpPath.fileInventory, SessionStore.token)
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
            "Updated At",
            ""
          )
          // Fixing a bad ingest voids and reverses ledger entries, so it's offered to admins only —
          // the same restriction the page had when it lived in the sidebar's Admin group.
          val isAdmin = SessionStore.currentUserNow.exists(_.role == "Admin")
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
              span(entry.updatedAt.map(ViewHelper.formatDateTime).getOrElse("-")),
              if isAdmin then
                Buttons.textButton("Fix bad ingest…", Observer[Unit](_ => Nav.toFixBadFile(entry.fileName)))
              else span()
            )
          }
          Table.create(headers, rows)
        }
      )
    )

  case class State(entries: Seq[FileInventoryPayload] = Seq.empty)
