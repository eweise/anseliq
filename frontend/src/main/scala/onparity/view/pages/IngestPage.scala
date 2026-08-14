package onparity.view.pages

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.scalajs.dom.{FileReader, HTMLInputElement}
import onparity.common.http.HttpPath
import onparity.common.model.{MovementSpecPayload, ReconciliationSpecPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.components.Buttons

object IngestPage:

  enum UploadStatus:
    case Idle, Reading, Uploading, Done
    case Failed(msg: String)

  def apply(accountId: String, reconSpecId: String): HtmlElement =
    AppState.activeFundFlow.now() match
      case None     => div(onMountCallback(_ => Nav.toFundFlows()))
      case Some(ff) => renderPage(accountId, reconSpecId, ff)

  private def renderPage(accountId: String, reconSpecId: String, ff: onparity.common.model.FundFlowPayload): HtmlElement =
    val reconSpec     = Var(ReconciliationSpecPayload())
    val movementSpecs = Var(Seq.empty[MovementSpecPayload])
    val statuses      = Var(Map.empty[String, UploadStatus])
    val ffId          = ff.id.getOrElse("")
    val fileReadBus   = EventBus[(String, String)]()

    div(
      ApiClient.get[Seq[ReconciliationSpecPayload]](
        HttpPath.url(s"${HttpPath.account}/$accountId/recon-specs"),
        SessionStore.token
      ) --> {
        case Right(specs) => specs.find(_.id.contains(reconSpecId)).foreach(reconSpec.set)
        case Left(e)      => AppState.globalError.set(Some(e))
      },
      ApiClient.get[Seq[MovementSpecPayload]](
        HttpPath.url(s"${HttpPath.fundFlows}/$ffId/movement-specs"),
        SessionStore.token
      ) --> {
        case Right(specs) => movementSpecs.set(specs)
        case Left(e)      => AppState.globalError.set(Some(e))
      },
      fileReadBus.events.flatMapSwitch { (specId, content) =>
        statuses.update(_.updated(specId, UploadStatus.Uploading))
        ApiClient
          .postRaw(HttpPath.url(s"${HttpPath.movementSpec}/$specId/ingest"), content, SessionStore.token)
          .map(result => (specId, result))
      } --> {
        case (specId, Right(_)) => statuses.update(_.updated(specId, UploadStatus.Done))
        case (specId, Left(e))  => statuses.update(_.updated(specId, UploadStatus.Failed(e)))
      },
      cls := "min-h-screen bg-md-surface-variant dark:bg-gray-900 flex items-start justify-center pt-20 px-4",
      div(
        cls := "bg-white dark:bg-gray-800 rounded-xl shadow-md w-full max-w-lg p-8",
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-2", "Ingest Files"),
        child <-- movementSpecs.signal.combineWith(reconSpec.signal).map { (specs, rs) =>
          val relevantIds = Set(rs.inMovementSpecId, rs.outMovementSpecId)
          val relevant    = specs.filter(ms => ms.id.exists(relevantIds.contains)).distinctBy(_.id)
          div(cls := "space-y-4").amend(
            relevant.map { ms =>
              val specId = ms.id.getOrElse("")
              div(
                cls := "border border-md-outline rounded-lg p-4",
                div(
                  cls := "flex items-center justify-between mb-3",
                  child <-- statuses.signal.map(m => statusBadge(m.getOrElse(specId, UploadStatus.Idle)))
                ),
                input(
                  idAttr := s"file-input-$specId",
                  typ    := "file",
                  cls    := "block w-full text-sm text-md-on-surface-variant file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-medium file:bg-md-primary file:text-md-on-primary hover:file:bg-md-primary-hover",
                  onChange --> { _ =>
                    statuses.update(_.updated(specId, UploadStatus.Reading))
                    val fileInput = dom.document.getElementById(s"file-input-$specId").asInstanceOf[HTMLInputElement]
                    val file      = fileInput.files(0)
                    val reader    = new FileReader()
                    reader.onload  = _ => fileReadBus.emit((specId, reader.result.asInstanceOf[String]))
                    reader.onerror = _ => statuses.update(_.updated(specId, UploadStatus.Failed("Failed to read file")))
                    reader.readAsText(file)
                  }
                )
              )
            }*
          )
        },
        div(cls := "flex justify-end mt-6", Buttons.textButton("Back", Observer[Unit](_ => Nav.back())))
      )
    )

  private def statusBadge(status: UploadStatus): HtmlElement = status match
    case UploadStatus.Idle      => span()
    case UploadStatus.Reading   => span(cls := "text-xs text-blue-500", "Reading...")
    case UploadStatus.Uploading => span(cls := "text-xs text-blue-500", "Uploading...")
    case UploadStatus.Done      => span(cls := "text-xs text-green-600 font-medium", "Done")
    case UploadStatus.Failed(e) => span(cls := "text-xs text-red-500", s"Error: $e")
