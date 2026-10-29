package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.LedgerPayload
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.ViewHelper
import anseliq.view.components.{Buttons, PageHeader, Table}
import upickle.default.*

object LedgersPage:

  def apply(): HtmlElement =
    val ledgers = Var(Seq.empty[LedgerPayload])

    PageHeader.page(
      ApiClient.get[Seq[LedgerPayload]](HttpPath.ledgers, SessionStore.token).apiBind { loaded =>
        ledgers.set(loaded)
        AppState.ledgers.set(loaded)
      },
      PageHeader.titleRow(
        cls := "flex justify-between items-center",
        PageHeader.title("Ledgers"),
        Option.when(SessionStore.canEdit)(
          Buttons.linkButton(
            "Create",
            Observer[Unit](_ =>
              AppState.activeLedger.set(None)
              Nav.toMaintainLedger()
            )
          )
        )
      ),
      div(
        cls := "w-full px-8",
        child <-- ledgers.signal.map { ledgers =>
          // Every cell (not just Name) carries its own click-to-navigate handler, so the whole row
          // is clickable — Table is a shared component with no row-level click plumbing, and adding
          // it there would need to survive this page's own sort-by-column reordering, whereas
          // keeping the handler bundled into each cell's own HtmlElement means it just travels along
          // with that cell when Table re-sorts the display order. The hover *highlight* is still
          // driven by Table itself (hoverableRows), so the whole row lights up as one bar rather
          // than three separately-hovering cells.
          def cell(content: String, ledger: LedgerPayload): HtmlElement =
            a(
              href := "/components/ledger",
              cls  := "block px-4 py-2 text-sm text-gray-600 dark:text-gray-300",
              onClick --> { e =>
                e.preventDefault()
                Nav.toLedger(ledger)
              },
              content
            )

          Table.create(
            Seq("Name", "Created At", "Updated At", "", ""),
            ledgers.sortBy(_.name).map { ledger =>
              Seq(
                cell(ledger.name, ledger),
                cell(ledger.createdAt.map(ViewHelper.formatDateTime).getOrElse(""), ledger),
                cell(ledger.updatedAt.map(ViewHelper.formatDateTime).getOrElse(""), ledger)
              )
            },
            hoverableRows = true
          )
        }
      )
    )
