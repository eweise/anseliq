package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{BadFilePreviewPayload, BadFilePreviewRequest, FixBadFileRequest, FixBadFileResultPayload}
import anseliq.http.ApiClient
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.{Buttons, PageHeader}

object FixBadFilePage:

  case class State(
    preview: Option[BadFilePreviewPayload]   = None,
    result:  Option[FixBadFileResultPayload] = None
  )

  def apply(): HtmlElement =
    // Reached from a File Inventory row's "Fix bad ingest…" with ?file=<that file's name>, so the
    // name is filled in and its preview runs straight away; typing a name by hand still works.
    val initialFile = Option(new org.scalajs.dom.URLSearchParams(org.scalajs.dom.window.location.search).get("file"))
      .filter(_.nonEmpty)
    val state       = Var(State())
    val fileNameVar = Var(initialFile.getOrElse(""))
    val previewBus  = EventBus[String]()
    val fixBus      = EventBus[String]()

    PageHeader.page(
      previewBus.events.flatMapSwitch { fileSourceEntry =>
        ApiClient.post[BadFilePreviewPayload, BadFilePreviewRequest](
          s"${HttpPath.movements}/bad-file-preview",
          BadFilePreviewRequest(fileSourceEntry),
          SessionStore.token
        )
      }.apiBind(p => state.update(_.copy(preview = Some(p), result = None))),
      fixBus.events.flatMapSwitch { fileSourceEntry =>
        ApiClient.post[FixBadFileResultPayload, FixBadFileRequest](
          s"${HttpPath.movements}/bad-file-fix",
          FixBadFileRequest(fileSourceEntry),
          SessionStore.token
        )
      }.apiBind(r => state.update(_.copy(result = Some(r), preview = None))),
      initialFile.map(f => EventStream.fromValue(f) --> previewBus.writer).getOrElse(emptyMod),
      PageHeader.titleRow(
        PageHeader.breadcrumb(
          "File Inventory" -> Some(Observer[Unit](_ => anseliq.routing.Nav.push("/components/file-inventory"))),
          "Fix bad ingest" -> None
        ),
        PageHeader.title("Fix bad ingest")
      ),
      div(
        cls := "w-full px-8 max-w-2xl",
        p(
          cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mb-4",
          "Enter the exact file name a bad ingest was tagged with (e.g. a misconfigured amount " +
            "field). This voids whatever reconciliations those movements were matched into and " +
            "posts reversing entries for each one — nothing is ever deleted, so the original " +
            "entries stay in the ledger as a permanent record."
        ),
        div(
          cls := "flex gap-2 mb-6",
          input(
            typ         := "text",
            placeholder := "file name",
            value       := fileNameVar.now(),
            cls         := "flex-1 border border-gray-300 dark:border-gray-600 rounded px-3 py-2 text-sm dark:bg-gray-800 dark:text-white",
            onInput.mapToValue --> Observer[String](v => fileNameVar.set(v))
          ),
          Buttons.textButton("Preview", Observer[Unit](_ => previewBus.emit(fileNameVar.now())))
        ),
        child <-- state.signal.map {
          case s if s.result.isDefined  => resultView(s.result.get)
          case s if s.preview.isDefined => previewView(s.preview.get, fileNameVar, fixBus)
          case _                        => div()
        }
      )
    )

  private def previewView(
    preview:     BadFilePreviewPayload,
    fileNameVar: Var[String],
    fixBus:      EventBus[String]
  ): HtmlElement =
    div(
      cls := "border border-gray-200 dark:border-gray-700 rounded p-4",
      p(s"${preview.movementsFound} movement(s) found for this file."),
      p(s"${preview.reconciledCount} currently reconciled — will be voided first."),
      p(s"${preview.alreadyReversedCount} already a reversal entry — left untouched."),
      p(cls := "font-medium mt-2", s"${preview.eligibleForReversalCount} will be reversed."),
      if preview.eligibleForReversalCount == 0 && preview.reconciledCount == 0 then
        p(cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mt-2", "Nothing to do.")
      else
        div(
          cls := "mt-4",
          Buttons.linkButton("Confirm", Observer[Unit](_ => fixBus.emit(fileNameVar.now())))
        )
    )

  private def resultView(result: FixBadFileResultPayload): HtmlElement =
    div(
      cls := "border border-gray-200 dark:border-gray-700 rounded p-4",
      p(s"${result.reconciliationsVoided} reconciliation(s) voided."),
      p(s"${result.movementsReversed} movement(s) reversed."),
      p(cls := "text-sm text-md-on-surface-variant dark:text-gray-400 mt-2", "Done.")
    )
