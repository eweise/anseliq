package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{AccountPayload, MovementPayload, MovementSpecPayload}
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.{Buttons, Table}
import upickle.default.*

object UnreconciledMovementsPage:

  case class State(
    accounts:  Seq[AccountPayload]  = Seq.empty,
    movements: Seq[MovementPayload] = Seq.empty
  )

  def apply(): HtmlElement =
    val spec   = AppState.editMovementSpec.now().getOrElse(MovementSpecPayload())
    val specId = spec.id.getOrElse("")
    val ffId   = AppState.activeFundFlow.now().flatMap(_.id).getOrElse("")
    val state  = Var(State())

    div(
      ApiClient.get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token) --> {
        case Right(accs) => state.update(_.copy(accounts = accs))
        case Left(e)     => AppState.globalError.set(Some(e))
      },
      ApiClient.get[Seq[MovementPayload]](HttpPath.url(s"${HttpPath.movements}/$specId/unreconciled"), SessionStore.token) --> {
        case Right(ms) => state.update(_.copy(movements = ms))
        case Left(e)   => AppState.globalError.set(Some(e))
      },

      cls := "flex flex-col items-center mb-8 pt-20",
      div(cls := "my-8 text-center",
        h2(cls := "text-2xl font-medium mb-6", s"${spec.name} — Unreconciled Movements"),
        Buttons.linkButton("Back", Observer[Unit](_ => Nav.back())),
        child <-- state.signal.map { s =>
          val nameById = s.accounts.flatMap(a => a.id.map(_ -> a.name)).toMap
          Table.create(
            Seq("Reference ID", "Amount", "In Account", "Out Account"),
            s.movements.map(m => Seq(
              span(m.referenceId),
              span(m.amount.toString),
              span(nameById.getOrElse(m.inAccountId, m.inAccountId)),
              span(nameById.getOrElse(m.outAccountId, m.outAccountId))
            ))
          )
        }
      )
    )
