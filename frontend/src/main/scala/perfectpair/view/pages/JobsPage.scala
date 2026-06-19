package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.*
import perfectpair.common.model.JobInfoStatus.Complete
import perfectpair.common.model.JobTypeInfo.{CreateFileInventory, Ingestion, Reconciliation}
import perfectpair.http.ApiClient
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.ViewHelper
import perfectpair.view.components.{Form, Table}
import upickle.default.*

object JobsPage:

  def apply(): HtmlElement =
    val state    = Var(State())
    val fetchBus = EventBus[String]()

    div(
      EventStream
        .merge(
          EventStream.fromValue(JobTypeInfo.Reconciliation.toString, emitOnce = true),
          fetchBus.events
        )
        .flatMapSwitch { jobType =>
          ApiClient.get[Seq[JobInfoPayload]](HttpPath.url(s"${HttpPath.jobs}/$jobType"), SessionStore.token)
        } --> {
        case Right(jobs) => state.update(_.copy(jobs = jobs))
        case Left(e)     => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20 px-8 w-full",
      div(
        cls := "w-full max-w-5xl",
        h2(cls := "text-2xl font-medium mb-6", "Jobs"),
        div(
          cls := "mb-6 max-w-xs",
          Form.selectField(
            "job-type-select",
            "Job Type",
            JobTypeInfo.values.toSeq.map(jt => jt.toString -> jt.toString),
            state.now().selectedJobType.toString,
            v =>
              state.update(s => s.copy(selectedJobType = JobTypeInfo.valueOf(v), jobs = Seq.empty))
              fetchBus.emit(v)
          )
        ),
        child <-- state.signal.map { s =>
          val extraHeaders = s.selectedJobType match
            case Reconciliation => Seq("Account Name", "Record Count", "Processing Time (seconds)")
            case Ingestion =>
              Seq("Records Ingested", "Processing Time (Seconds)")
            case CreateFileInventory => Seq("Files Queued")
          val headers = Seq("Status", "Error", "Created At") ++ extraHeaders
          val rows = s.jobs
            .filter(j => j.jobStatus == Complete || j.jobStatus == JobInfoStatus.Error)
            .map { job =>
              val base: Seq[HtmlElement] = Seq(
                span(job.jobStatus.toString),
                span(job.errorMessage.getOrElse("-")),
                span(job.createdAt.map(ViewHelper.formatDateTime).getOrElse("-"))
              )
              base ++ extraCells(job, s.selectedJobType)
            }
          Table.create(headers, rows)
        }
      )
    )

  private def extraCells(job: JobInfoPayload, jobType: JobTypeInfo): Seq[HtmlElement] = jobType match
    case Reconciliation =>
      job.jobInfoJson
        .flatMap(parseJobInfo)
        .collect { case ri: ReconciliationInfo =>
          Seq(
            span(ri.accountName),
            span(ri.reconciliationsCreatedCount.toString),
            span(ri.processingTimeSeconds.toString)
          )
        }
        .getOrElse(Seq(span("-")))
    case Ingestion =>
      job.jobInfoJson
        .flatMap(parseJobInfo)
        .collect { case ii: IngestionInfo =>
          Seq(
            span(ii.numberOfRecordsIngested.toString),
            span(ii.processingTimeSeconds.toString)
          )
        }
        .getOrElse(Seq.fill(2)(span("-")))
    case CreateFileInventory =>
      job.jobInfoJson
        .flatMap(parseJobInfo)
        .collect { case ii: FileInventoryInfo =>
          Seq(span(ii.filesQueued.toString))
        }
        .getOrElse(Seq(span("-")))

  private def parseJobInfo(json: String): Option[JobInfo] = scala.util.Try(read[JobInfo](json)).toOption

  case class State(
    selectedJobType: JobTypeInfo         = JobTypeInfo.Reconciliation,
    jobs:            Seq[JobInfoPayload] = Seq.empty
  )
