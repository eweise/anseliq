package onparity.view.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.scalajs.dom.{FileReader, HTMLInputElement}
import onparity.common.http.HttpPath
import onparity.common.model.MovementSpecPayload
import onparity.http.ApiClient
import onparity.view.components.{Buttons, Card, Form}
import onparity.state.SessionStore

/**
 * "Ingest file" from the ledger page header: upload a file into any of the ledger's movement specs.
 * Replaces the old IngestPage, which was only reachable from deep inside a recon spec's pages and
 * only offered that one spec's two movement specs. Rendered in a Modal; `onClose` also runs after
 * the user is done so the ledger can refresh its balances.
 */
object IngestDialog:

  private enum UploadStatus:
    case Idle, Reading, Uploading, Done
    case Failed(msg: String)

  def apply(movementSpecs: Seq[MovementSpecPayload], onClose: () => Unit): HtmlElement =
    val statuses    = Var(Map.empty[String, UploadStatus])
    val fileReadBus = EventBus[(String, String)]()

    div(
      fileReadBus.events.flatMapSwitch { (specId, content) =>
        statuses.update(_.updated(specId, UploadStatus.Uploading))
        ApiClient
          .postRaw(HttpPath.url(s"${HttpPath.movementSpec}/$specId/ingest"), content, SessionStore.token)
          .map(result => (specId, result))
      } --> {
        case (specId, Right(_)) => statuses.update(_.updated(specId, UploadStatus.Done))
        case (specId, Left(e))  => statuses.update(_.updated(specId, UploadStatus.Failed(e)))
      },
      h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-2", "Ingest file"),
      p(
        cls := s"text-sm ${Form.mutedTextCls} mb-6",
        "Choose the movement spec the file belongs to. Its file feed's parser reads it, and the movements are posted to this ledger."
      ),
      if movementSpecs.isEmpty then p(cls := s"text-sm ${Form.mutedTextCls}", "This ledger has no movement specs yet. Add one in Setup.")
      else
        div(
          cls := "flex flex-col gap-4",
          movementSpecs.sortBy(_.name).map { ms =>
            val specId = ms.id.getOrElse("")
            div(
              cls := s"${Card.cls} p-4 flex flex-col gap-3",
              div(
                cls := "flex items-center justify-between gap-3",
                label(forId := s"file-input-$specId", cls := "font-medium text-md-on-surface dark:text-white", ms.name),
                child <-- statuses.signal.map(m => statusBadge(m.getOrElse(specId, UploadStatus.Idle)))
              ),
              input(
                idAttr := s"file-input-$specId",
                typ    := "file",
                cls    := "block w-full text-sm text-md-on-surface-variant file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-medium file:bg-md-primary file:text-md-on-primary hover:file:bg-md-primary-hover",
                onChange --> { _ =>
                  val fileInput = dom.document.getElementById(s"file-input-$specId").asInstanceOf[HTMLInputElement]
                  if fileInput.files.length > 0 then
                    statuses.update(_.updated(specId, UploadStatus.Reading))
                    val reader = new FileReader()
                    reader.onload  = _ => fileReadBus.emit((specId, reader.result.asInstanceOf[String]))
                    reader.onerror = _ => statuses.update(_.updated(specId, UploadStatus.Failed("Couldn't read the file")))
                    reader.readAsText(fileInput.files(0))
                }
              )
            )
          }
        ),
      div(cls := "flex justify-end mt-6", Buttons.textButton("Done", Observer[Unit](_ => onClose())))
    )

  private def statusBadge(status: UploadStatus): HtmlElement = status match
    case UploadStatus.Idle      => span()
    case UploadStatus.Reading   => span(cls := "text-xs text-md-on-surface-variant", "Reading…")
    case UploadStatus.Uploading => span(cls := "text-xs text-md-on-surface-variant", "Uploading…")
    case UploadStatus.Done      => span(cls := "text-xs font-medium text-md-good dark:text-md-good-dark", "Ingested")
    case UploadStatus.Failed(e) => span(cls := "text-xs text-md-critical dark:text-md-critical-dark", s"Failed: $e")
