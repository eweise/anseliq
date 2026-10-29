package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.common.model.JobTypeInfo.{CreateFileInventory, Ingestion, Reconciliation, SuggestMatches}
import anseliq.http.ApiClient
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.ViewHelper
import anseliq.view.components.{Card, PageHeader, Pill, Table}
import upickle.default.*

import java.time.Duration

// Airflow-style grid: one row per job type, one bar per run (oldest left, newest right), colored by
// status and scaled in height by how long the run took. Clicking a row's label shows that type's
// runs in the table below; clicking a bar shows that run's details.
object JobsPage:

  // Bars per row. Older runs are still in the table below, just not in the grid.
  private val GridRuns = 60

  def apply(): HtmlElement =
    val state      = Var(State())
    val refreshBus = EventBus[Unit]()

    PageHeader.page(
      EventStream
        .merge(EventStream.fromValue((), emitOnce = true), refreshBus.events)
        .flatMapSwitch { _ =>
          EventStream.merge(JobTypeInfo.values.toSeq.map { jt =>
            ApiClient
              .get[Seq[JobInfoPayload]](HttpPath.url(s"${HttpPath.jobs}/$jt"), SessionStore.token)
              .map(_.map(jt -> _))
          }*)
        }
        .apiBind((jt, jobs) => state.update(s => s.copy(jobsByType = s.jobsByType + (jt -> jobs)))),
      PageHeader.titleRow(
        cls := "flex items-center justify-between",
        PageHeader.title("Jobs"),
        PageHeader.refreshButton(Observer[Unit](_ => refreshBus.emit(())))
      ),
      div(
        cls := "w-full px-8 space-y-6",
        div(
          cls := s"${Card.cls} p-6",
          div(
            cls := "mb-4 flex items-center justify-between",
            h2(cls := "text-base font-semibold text-gray-900 dark:text-white", "Run history"),
            statusLegend
          ),
          div(
            cls := "space-y-2",
            JobTypeInfo.values.toSeq.map(jt => gridRow(jt, state))
          )
        ),
        child <-- state.signal.map(s => s.selectedJob.fold(emptyNode)(detailPanel)),
        div(
          cls := s"${Card.cls} p-6",
          h2(
            cls := "mb-4 text-base font-semibold text-gray-900 dark:text-white",
            child.text <-- state.signal.map(s => s"${s.selectedJobType} runs")
          ),
          child <-- state.signal.map(s => runsTable(s.selectedJobType, s.jobsFor(s.selectedJobType)))
        )
      )
    )

  private def gridRow(jobType: JobTypeInfo, state: Var[State]): HtmlElement =
    div(
      cls := "flex items-center gap-4",
      button(
        cls <-- state.signal.map { s =>
          val sel =
            if s.selectedJobType == jobType then "bg-gray-100 dark:bg-gray-700"
            else "hover:bg-gray-50 dark:hover:bg-gray-700/50"
          s"w-64 shrink-0 rounded-md px-3 py-2 text-left $sel"
        },
        onClick --> (_ => state.update(_.copy(selectedJobType = jobType))),
        div(cls := "text-sm font-medium text-gray-900 dark:text-white", jobType.toString),
        div(
          cls := "text-xs text-gray-500 dark:text-gray-400",
          child.text <-- state.signal.map(s => runSummary(s.jobsFor(jobType)))
        )
      ),
      div(
        cls := "flex h-8 flex-1 items-end justify-end gap-0.5 overflow-hidden",
        children <-- state.signal.map { s =>
          // The API returns newest first; the grid reads left to right in time.
          val runs   = s.jobsFor(jobType).take(GridRuns).reverse
          val maxSec = runs.flatMap(durationSeconds).maxOption.getOrElse(0L)
          if runs.isEmpty then Seq(span(cls := "text-xs text-gray-400 self-center", "No runs yet"))
          else runs.map(job => bar(job, maxSec, s.selectedJob.exists(_.id == job.id), state))
        }
      )
    )

  private def bar(job: JobInfoPayload, maxSec: Long, selected: Boolean, state: Var[State]): HtmlElement =
    // Height shows relative duration within the row, with a floor so quick runs stay clickable.
    val pct  = durationSeconds(job).filter(_ => maxSec > 0).map(d => 35 + (65 * d / maxSec)).getOrElse(100L)
    val ring = if selected then "ring-2 ring-offset-1 ring-gray-900 dark:ring-white dark:ring-offset-gray-800" else ""
    button(
      cls       := s"w-2.5 shrink-0 rounded-sm hover:opacity-70 ${statusBg(RunStatus.of(job))} $ring",
      styleAttr := s"height: $pct%",
      title     := tooltip(job),
      onClick --> (_ => state.update(_.copy(selectedJob = Some(job))))
    )

  private def detailPanel(job: JobInfoPayload): HtmlElement =
    val info = job.jobInfoJson.flatMap(parseJobInfo)
    val fields: Seq[(String, String)] =
      Seq(
        "Job type"    -> job.jobType.toString,
        "Started"     -> job.createdAt.map(ViewHelper.formatDateTime).getOrElse("-"),
        "Last update" -> job.updatedAt.map(ViewHelper.formatDateTime).getOrElse("-"),
        "Duration"    -> durationSeconds(job).map(formatSeconds).getOrElse("-"),
        "Reference"   -> (if job.referenceId.isEmpty then "-" else job.referenceId),
        "Attempts"    -> job.attempts.toString
      ) ++ retryFields(job) ++ infoFields(info)
    div(
      cls := s"${Card.cls} p-6",
      div(
        cls := "mb-4 flex items-center gap-3",
        h2(cls := "text-base font-semibold text-gray-900 dark:text-white", "Run details"),
        statusPill(RunStatus.of(job))
      ),
      job.errorMessage.map { msg =>
        pre(
          cls := "mb-4 whitespace-pre-wrap break-words rounded-md bg-md-critical-bg dark:bg-md-critical-bg-dark p-3 text-sm text-md-critical dark:text-md-critical-dark",
          msg
        )
      },
      dl(
        cls := "grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4",
        fields.map { (label, value) =>
          div(
            dt(cls := "text-xs text-gray-500 dark:text-gray-400", label),
            dd(cls := "text-sm text-gray-900 dark:text-white break-all", value)
          )
        }
      )
    )

  private def runsTable(jobType: JobTypeInfo, jobs: Seq[JobInfoPayload]): HtmlElement =
    val extraHeaders = jobType match
      case Reconciliation      => Seq("Account Name", "Record Count", "Processing Time (seconds)")
      case Ingestion           => Seq("Records Ingested", "Processing Time (Seconds)")
      case CreateFileInventory => Seq("Files Queued")
      case SuggestMatches      => Seq("Movements Considered", "Suggestions Created")
    val headers = Seq("Status", "Started", "Error") ++ extraHeaders
    val rows = jobs.map { job =>
      Seq(
        statusPill(RunStatus.of(job)),
        span(job.createdAt.map(ViewHelper.formatDateTime).getOrElse("-")),
        span(job.errorMessage.getOrElse("-"))
      ) ++ extraCells(job, jobType)
    }
    Table.create(headers, rows)

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
        .getOrElse(Seq.fill(3)(span("-")))
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
    case SuggestMatches =>
      job.jobInfoJson
        .flatMap(parseJobInfo)
        .collect { case ms: MatchSuggestionInfo =>
          Seq(span(ms.movementsConsidered.toString), span(ms.suggestionsCreated.toString))
        }
        .getOrElse(Seq.fill(2)(span("-")))

  private def retryFields(job: JobInfoPayload): Seq[(String, String)] =
    if RunStatus.of(job) == RunStatus.Retrying then
      Seq("Next retry" -> job.runAt.map(ViewHelper.formatDateTime).getOrElse("-"))
    else Seq.empty

  private def infoFields(info: Option[JobInfo]): Seq[(String, String)] = info match
    case Some(ri: ReconciliationInfo) =>
      Seq("Account" -> ri.accountName, "Reconciliations created" -> ri.reconciliationsCreatedCount.toString)
    case Some(ii: IngestionInfo)     => Seq("Records ingested" -> ii.numberOfRecordsIngested.toString)
    case Some(fi: FileInventoryInfo) => Seq("Files queued" -> fi.filesQueued.toString)
    case Some(rs: ReconStatsInfo)    => Seq("Ledgers snapshotted" -> rs.ledgersSnapshotted.toString)
    case Some(ms: MatchSuggestionInfo) =>
      Seq(
        "Movements considered" -> ms.movementsConsidered.toString,
        "Suggestions created"  -> ms.suggestionsCreated.toString
      )
    case None => Seq.empty

  // A Submitted job with an error message is one whose last attempt failed and that is waiting out
  // its backoff (see JobManager.retryOrFail), so it's shown as Retrying rather than Queued.
  private enum RunStatus:
    case Queued, Running, Retrying, Success, Failed

  private object RunStatus:
    def of(job: JobInfoPayload): RunStatus = job.jobStatus match
      case JobInfoStatus.Submitted if job.errorMessage.isDefined => Retrying
      case JobInfoStatus.Submitted                               => Queued
      case JobInfoStatus.InProgress                              => Running
      case JobInfoStatus.Complete                                => Success
      case JobInfoStatus.Error                                   => Failed

  private val statusLegend: HtmlElement =
    div(
      cls := "flex items-center gap-4 text-xs text-gray-600 dark:text-gray-300",
      RunStatus.values.toSeq.map { st =>
        span(
          cls := "flex items-center gap-1.5",
          span(cls := s"inline-block h-3 w-3 rounded-sm ${statusBg(st)}"),
          st.toString
        )
      }
    )

  private def statusBg(status: RunStatus): String = status match
    case RunStatus.Success  => "bg-md-good dark:bg-md-good-dark"
    case RunStatus.Failed   => "bg-md-critical dark:bg-md-critical-dark"
    case RunStatus.Running  => "bg-md-warning dark:bg-md-warning-dark"
    case RunStatus.Retrying => "bg-md-serious dark:bg-md-serious-dark"
    case RunStatus.Queued   => "bg-gray-300 dark:bg-gray-600"

  private def statusPill(status: RunStatus): HtmlElement =
    val tone = status match
      case RunStatus.Success  => Pill.Tone.Good
      case RunStatus.Failed   => Pill.Tone.Critical
      case RunStatus.Running  => Pill.Tone.Warning
      case RunStatus.Retrying => Pill.Tone.Serious
      case RunStatus.Queued   => Pill.Tone.Neutral
    Pill(status.toString, tone)

  private def runSummary(jobs: Seq[JobInfoPayload]): String =
    val finished = jobs.filter(j => j.jobStatus == JobInfoStatus.Complete || j.jobStatus == JobInfoStatus.Error)
    if finished.isEmpty then s"${jobs.size} runs"
    else
      val rate = 100 * finished.count(_.jobStatus == JobInfoStatus.Complete) / finished.size
      s"${jobs.size} runs · $rate% success"

  private def tooltip(job: JobInfoPayload): String =
    Seq(
      Some(RunStatus.of(job).toString),
      job.createdAt.map(ViewHelper.formatDateTime),
      durationSeconds(job).map(formatSeconds),
      job.jobInfoJson.flatMap(parseJobInfo).collect { case ri: ReconciliationInfo => ri.accountName },
      job.errorMessage
    ).flatten.mkString("\n")

  private def durationSeconds(job: JobInfoPayload): Option[Long] =
    for
      start <- job.createdAt
      end   <- job.updatedAt
    yield Duration.between(start, end).getSeconds.max(0L)

  private def formatSeconds(s: Long): String =
    if s < 60 then s"${s}s" else if s < 3600 then s"${s / 60}m ${s % 60}s" else s"${s / 3600}h ${(s % 3600) / 60}m"

  private def parseJobInfo(json: String): Option[JobInfo] = scala.util.Try(read[JobInfo](json)).toOption

  case class State(
    selectedJobType: JobTypeInfo                           = JobTypeInfo.Reconciliation,
    selectedJob:     Option[JobInfoPayload]                = None,
    jobsByType:      Map[JobTypeInfo, Seq[JobInfoPayload]] = Map.empty
  ):
    def jobsFor(jt: JobTypeInfo): Seq[JobInfoPayload] = jobsByType.getOrElse(jt, Seq.empty)
