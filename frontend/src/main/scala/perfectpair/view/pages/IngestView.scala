package perfectpair.view.pages

import org.scalajs.dom
import org.scalajs.dom.{FileReader, HTMLInputElement}
import perfectpair.common.model.{AccountPayload, MovementSpecPayload, ReconciliationSpecPayload}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.HttpHelper
import perfectpair.view.components.Buttons
import tyrian.Html.*
import tyrian.{Cmd, Html}
import zio.interop.catz.*
import zio.{Task, ZIO}

object IngestView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.FileSelected(specId) =>
        (
          model.copy(uploads = model.uploads.updated(specId, UploadStatus.Reading)),
          readFileCmd(specId)
        )
      case Msg.FileContent(specId, content) =>
        (
          model.copy(uploads = model.uploads.updated(specId, UploadStatus.Uploading)),
          HttpHelper.ingestFile(specId, content)
        )
      case Msg.IngestComplete(specId) =>
        (model.copy(uploads = model.uploads.updated(specId, UploadStatus.Done)), Cmd.None)
      case Msg.IngestError(specId, err) =>
        (model.copy(uploads = model.uploads.updated(specId, UploadStatus.Failed(err))), Cmd.None)

  private def readFileCmd(movementSpecId: String): Cmd[Task, AppMsg] =
    Cmd.Run(
      ZIO.async[Any, Throwable, AppMsg] { cb =>
        val input  = dom.document.getElementById(s"file-input-$movementSpecId").asInstanceOf[HTMLInputElement]
        val file   = input.files(0)
        val reader = new FileReader()
        reader.onload = _ =>
          cb(ZIO.succeed(AppMsg.IngestViewMsg(Msg.FileContent(movementSpecId, reader.result.asInstanceOf[String]))))
        reader.onerror =
          _ => cb(ZIO.succeed(AppMsg.IngestViewMsg(Msg.IngestError(movementSpecId, "Failed to read file"))))
        reader.readAsText(file)
      }
    )

  def apply(model: Model): Html[AppMsg] =
    div(cls := "min-h-screen bg-md-surface-variant flex items-start justify-center pt-20 px-4")(
      div(cls := "bg-white rounded-xl shadow-md w-full max-w-lg p-8")(
        h2(cls := "text-xl font-medium text-md-on-surface mb-2")(text("Ingest Files")),
        div(cls := "space-y-4")(
          model.relevantSpecs.map { spec =>
            val specId = spec.id.getOrElse("")
            val status = model.uploads.getOrElse(specId, UploadStatus.Idle)
            div(cls := "border border-md-outline rounded-lg p-4")(
              div(cls := "flex items-center justify-between mb-3")(
                statusBadge(status)
              ),
              input(
                id  := s"file-input-$specId",
                tpe := "file",
                cls := "block w-full text-sm text-md-on-surface-variant file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-medium file:bg-md-primary file:text-md-on-primary hover:file:bg-md-primary-hover",
                onChange(_ => AppMsg.IngestViewMsg(Msg.FileSelected(specId)))
              )
            )
          }*
        ),
        div(cls := "flex justify-end mt-6")(
          Buttons.textButton("Back", AppMsg.NavigateToReconSpecs(model.account))
        )
      )
    )

  private def statusBadge(status: UploadStatus): Html[AppMsg] =
    status match
      case UploadStatus.Idle        => span()(text(""))
      case UploadStatus.Reading     => span(cls := "text-xs text-blue-500")(text("Reading..."))
      case UploadStatus.Uploading   => span(cls := "text-xs text-blue-500")(text("Uploading..."))
      case UploadStatus.Done        => span(cls := "text-xs text-green-600 font-medium")(text("Done"))
      case UploadStatus.Failed(err) => span(cls := "text-xs text-red-500")(text(s"Error: $err"))

  enum UploadStatus:
    case Idle
    case Reading
    case Uploading
    case Done
    case Failed(msg: String)

  enum Msg:
    case FileSelected(movementSpecId: String)
    case FileContent(movementSpecId: String, content: String)
    case IngestComplete(movementSpecId: String)
    case IngestError(movementSpecId: String, err: String)

  case class Model(
    account:       AccountPayload            = AccountPayload(),
    reconSpec:     ReconciliationSpecPayload = ReconciliationSpecPayload(),
    movementSpecs: Seq[MovementSpecPayload]  = Seq(),
    uploads:       Map[String, UploadStatus] = Map()
  ):
    def relevantSpecs: Seq[MovementSpecPayload] =
      val ids = Set(reconSpec.inMovementSpecId, reconSpec.outMovementSpecId)
      movementSpecs.filter(ms => ms.id.exists(ids.contains)).distinctBy(_.id)
