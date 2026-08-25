package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.*
import onparity.common.model.JobInfoStatus.Complete
import onparity.common.model.JobTypeInfo.{CreateFileInventory, Ingestion, Reconciliation}
import onparity.http.ApiClient
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.ViewHelper
import onparity.view.components.{Form, Icons, PageHeader, Table}
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
        }
        .apiBind(jobs => state.update(_.copy(jobs = jobs))),
      cls := "flex flex-col mb-8 pt-20",
      div(
        cls := "w-full px-8",
        PageHeader.title("Jobs"),
        div(
          cls := "mb-6 flex items-end gap-3",
          div(
            cls := "max-w-xs",
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
          button(
            cls := "flex border border-gray-200 bg-green-400 text-white justify-center items-center w-10 h-10 rounded-full hover:bg-green-500",
            typ := "button",
            onClick --> (_ => fetchBus.emit(state.now().selectedJobType.toString)),
            Icons.refresh
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
