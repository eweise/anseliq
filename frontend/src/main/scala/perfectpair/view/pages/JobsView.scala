package perfectpair.view.pages

import perfectpair.common.model.JobTypeInfo.{CreateFileInventory, Ingestion, Reconciliation}
import perfectpair.common.model.{
  FileInventoryInfo,
  IngestionInfo,
  JobInfo,
  JobInfoPayload,
  JobTypeInfo,
  ReconciliationInfo
}
import perfectpair.model.Msg as AppMsg
import perfectpair.util.HttpHelper
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Form, Table}
import tyrian.Html.*
import tyrian.{Cmd, Elem, Html}
import upickle.default.*
import zio.Task

object JobsView:

  def update(model: Model, msg: Msg): (Model, Cmd[Task, AppMsg]) =
    msg match
      case Msg.SelectJobType(jobType) =>
        (model.copy(selectedJobType = JobTypeInfo.valueOf(jobType), jobs = Seq()), HttpHelper.fetchJobsByType(jobType))
      case Msg.JobsFromBackend(jobs) =>
        (model.copy(jobs = jobs), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    val extraHeaders = model.selectedJobType match
      case Reconciliation => reconExtraHeaders
      case Ingestion      => ingestionExtraHeaders
      case CreateFileInventory  => fileInventoryExtraHeaders
    val headers = Seq(
      "Status",
      "Error",
      "Created At"
    ) ++ extraHeaders

    val rows = model.jobs.map { job =>
      val baseCells: Seq[Elem[AppMsg]] = Seq(
        text(job.jobStatus.toString),
        text(job.errorMessage.getOrElse("-")),
        text(job.createdAt.map(ViewHelper.formatDateTime).getOrElse("-"))
      )
      baseCells ++ (job.jobType match
        case Reconciliation => reconExtraCells(job)
        case Ingestion      => ingestionExtraCells(job)
        case CreateFileInventory  => fileInventoryExtraCells(job)
      )
    }

    div(cls := "flex flex-col items-center mb-8 pt-20 px-8 w-full")(
      div(cls := "w-full max-w-5xl")(
        h2(cls := "text-2xl font-medium mb-6")(text("Jobs")),
        div(cls := "mb-6 max-w-xs")(
          Form.select(
            fieldID   = "job-type-select",
            labelName = "Job Type",
            options   = JobTypeInfo.values.map(jt => jt.toString -> jt.toString),
//            options       = Seq("reconciliation" -> "Reconciliation", "ingestion" -> "Ingestion", "createFileInventory" -> "CreateFileInventory"),
            selectedValue = model.selectedJobType.toString,
            onChangeFn    = v => AppMsg.JobsMsg(Msg.SelectJobType(v))
          )
        ),
        Table.create(headers, rows)
      )
    )

  private def reconExtraHeaders: Seq[String] =
    Seq("Account Name", "Record Count", "Processing Time (seconds)")

  private def ingestionExtraHeaders: Seq[String] =
    Seq("Spec Name", "Data Source", "File Path", "Records Ingested", "Processing Time (Seconds)")

  private def fileInventoryExtraHeaders: Seq[String] = Seq("Files Queued")

  private def reconExtraCells(job: JobInfoPayload): Seq[Elem[AppMsg]] =
    job.jobInfoJson.flatMap(parseJobInfo) match
      case Some(ri: ReconciliationInfo) =>
        Seq(
          text(ri.accountName),
          text(ri.reconciliationsCreatedCount.toString),
          text(ri.processingTimeSeconds.toString)
        )
      case _ => Seq(text("-"))

  private def parseJobInfo(json: String): Option[JobInfo] =
    scala.util.Try(read[JobInfo](json)).toOption

  private def ingestionExtraCells(job: JobInfoPayload): Seq[Elem[AppMsg]] =
    job.jobInfoJson.flatMap(parseJobInfo) match
      case Some(ii: IngestionInfo) =>
        Seq(
          text(ii.movementSpecName),
          text(ii.dataSource.toString),
          text(ii.filePath),
          text(ii.numberOfRecordsIngested.toString),
          text(ii.processingTimeSeconds.toString)
        )
      case _ => Seq.fill(5)(text("-"))

  private def fileInventoryExtraCells(job: JobInfoPayload): Seq[Elem[AppMsg]] =
    job.jobInfoJson.flatMap(parseJobInfo) match
      case Some(ii: FileInventoryInfo) =>
        Seq(
          text(ii.filesQueued.toString)
        )
      case _ => Seq.fill(5)(text("-"))

  enum Msg:
    case SelectJobType(jobType: String)
    case JobsFromBackend(jobs: Seq[JobInfoPayload])

  case class Model(
    selectedJobType: JobTypeInfo         = JobTypeInfo.Reconciliation,
    jobs:            Seq[JobInfoPayload] = Seq()
  )
