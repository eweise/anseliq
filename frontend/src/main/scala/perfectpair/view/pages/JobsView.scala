package perfectpair.view.pages

import perfectpair.common.model.{IngestionInfo, JobInfo, JobInfoPayload, ReconciliationInfo}
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
        (model.copy(selectedJobType = jobType, jobs = Seq()), HttpHelper.fetchJobsByType(jobType))
      case Msg.JobsFromBackend(jobs) =>
        (model.copy(jobs = jobs), Cmd.None)

  def apply(model: Model): Html[AppMsg] =
    val isRecon      = model.selectedJobType == "reconciliation"
    val extraHeaders = if isRecon then reconExtraHeaders else ingestionExtraHeaders
    val headers = Seq(
      "ID",
      "Status",
      "Error",
      "Created At",
      "Updated At"
    ) ++ extraHeaders

    val rows = model.jobs.map { job =>
      val baseCells: Seq[Elem[AppMsg]] = Seq(
        text(job.id.getOrElse("-")),
        text(job.jobStatus.toString),
        text(job.errorMessage.getOrElse("-")),
        text(job.createdAt.map(ViewHelper.formatDateTime).getOrElse("-")),
        text(job.updatedAt.map(ViewHelper.formatDateTime).getOrElse("-"))
      )
      baseCells ++ (if isRecon then reconExtraCells(job) else ingestionExtraCells(job))
    }

    div(cls := "flex flex-col items-center mb-8 pt-20 px-8 w-full")(
      div(cls := "w-full max-w-5xl")(
        h2(cls := "text-2xl font-medium mb-6")(text("Jobs")),
        div(cls := "mb-6 max-w-xs")(
          Form.select(
            fieldID       = "job-type-select",
            labelName     = "Job Type",
            options       = Seq("reconciliation" -> "Reconciliation", "ingestion" -> "Ingestion"),
            selectedValue = model.selectedJobType,
            onChangeFn    = v => AppMsg.JobsMsg(Msg.SelectJobType(v))
          )
        ),
        Table.create(headers, rows)
      )
    )

  private def reconExtraHeaders: Seq[String] =
    Seq("Reconciliation ID")

  private def ingestionExtraHeaders: Seq[String] =
    Seq("Spec Name", "Data Source", "File Path", "Records Ingested", "Processing Time (Seconds)")

  private def reconExtraCells(job: JobInfoPayload): Seq[Elem[AppMsg]] =
    job.jobInfoJson.flatMap(parseJobInfo) match
      case Some(ri: ReconciliationInfo) => Seq(text(ri.accountId))
      case _                            => Seq(text("-"))

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

  enum Msg:
    case SelectJobType(jobType: String)
    case JobsFromBackend(jobs: Seq[JobInfoPayload])

  case class Model(
    selectedJobType: String              = "reconciliation",
    jobs:            Seq[JobInfoPayload] = Seq()
  )
