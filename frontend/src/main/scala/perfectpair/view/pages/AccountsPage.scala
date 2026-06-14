package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.AccountPayload
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.{Buttons, Table}
import upickle.default.*

object AccountsPage:

  enum ReconcileStatus:
    case Idle, InProgress, Success
    case Failed(msg: String)

  case class State(
    accounts:          Seq[AccountPayload]          = Seq.empty,
    reconcileStatuses: Map[String, ReconcileStatus] = Map.empty
  )

  def apply(): HtmlElement =
    val fundFlow = AppState.activeFundFlow.now()
    val ffId     = fundFlow.flatMap(_.id).getOrElse("")
    val state    = Var(State())
    val reconcileBus = EventBus[String]()

    div(
      ApiClient.get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token) --> {
        case Right(accounts) => state.update(_.copy(accounts = accounts))
        case Left(err)       => AppState.globalError.set(Some(err))
      },

      reconcileBus.events
        .map { id =>
          state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.InProgress)))
          id
        }
        .flatMapSwitch { id =>
          ApiClient.postEmpty[ujson.Value](HttpPath.url(s"${HttpPath.account}/$id/reconcile"), SessionStore.token)
            .map(res => (id, res))
        } --> {
          case (id, Right(_)) =>
            state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.Success)))
          case (id, Left(e)) =>
            state.update(s => s.copy(reconcileStatuses = s.reconcileStatuses.updated(id, ReconcileStatus.Failed(e))))
        },

      cls := "flex flex-col items-center mb-8 pt-20",
      div(cls := "my-8 text-center",
        Buttons.linkButton("Add Account", Observer[Unit](_ => Nav.toMaintainAccount(None))),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Name", "Balance", "", "", "", ""),
            s.accounts.sortBy(_.name).map { account =>
              val accountId = account.id.getOrElse("")
              val status    = s.reconcileStatuses.getOrElse(accountId, ReconcileStatus.Idle)
              Seq(
                span(account.name),
                span(account.balance.toString),
                Buttons.textButton("Edit", Observer[Unit](_ => Nav.toMaintainAccount(account.id))),
                if account.isRecon then Buttons.textButton("Recon Specs", Observer[Unit](_ => Nav.toReconSpecs(accountId)))
                else div(),
                if account.isRecon then Buttons.textButton("Reconcile", Observer[Unit](_ => reconcileBus.emit(accountId)))
                else div(),
                statusBadge(status)
              )
            }
          )
        }
      )
    )

  private def statusBadge(status: ReconcileStatus): HtmlElement = status match
    case ReconcileStatus.Idle       => span()
    case ReconcileStatus.InProgress => span(cls := "text-xs text-blue-500", "Running…")
    case ReconcileStatus.Success    => span(cls := "text-xs text-green-600 font-medium", "Done")
    case ReconcileStatus.Failed(e)  => span(cls := "text-xs text-red-500", s"Error: $e")
