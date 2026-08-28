package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{AccountPayload, MovementPayload, ReconciliationSpecPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, PageHeader, Table}
import upickle.default.*

object UnreconciledMovementsPage:

  case class State(
    accounts:   Seq[AccountPayload]            = Seq.empty,
    movements:  Seq[MovementPayload]           = Seq.empty,
    reconSpecs: Seq[ReconciliationSpecPayload] = Seq.empty
  )

  def apply(accountId: String): HtmlElement =
    val ffId  = AppState.activeFundFlow.now().flatMap(_.id).getOrElse("")
    val state = Var(State())

    PageHeader.page(
      ApiClient
        .get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token)
        .apiBind(accs => state.update(_.copy(accounts = accs))),
      ApiClient
        .get[Seq[MovementPayload]](
          HttpPath.url(s"${HttpPath.account}/$accountId/unreconciled-movements"),
          SessionStore.token
        )
        .apiBind(ms => state.update(_.copy(movements = ms))),
      ApiClient
        .get[Seq[ReconciliationSpecPayload]](
          HttpPath.url(s"${HttpPath.account}/$accountId/recon-specs"),
          SessionStore.token
        )
        .apiBind(specs => state.update(_.copy(reconSpecs = specs))),
      PageHeader.titleRow(
        PageHeader.backLink(Observer[Unit](_ => Nav.back())),
        PageHeader.title(
          child.text <-- state.signal.map { s =>
            val name = s.accounts.find(_.id.contains(accountId)).map(_.name).getOrElse(accountId)
            s"$name — Unreconciled Movements"
          }
        )
      ),
      div(
        cls := "w-full px-8",
        Buttons.textButton(
          "Manual Match",
          Observer[Unit](_ =>
            val specId = state.now().reconSpecs.headOption.flatMap(_.id).getOrElse("")
            Nav.toManualMatch(accountId, specId)
          )
        ),
        child <-- state.signal.map { s =>
          val nameById = s.accounts.flatMap(a => a.id.map(_ -> a.name)).toMap
          Table.create(
            Seq("Reference ID", "Amount", "In Account", "Out Account"),
            s.movements.map(m =>
              Seq(
                span(m.referenceId),
                span(m.amount.toString),
                span(nameById.getOrElse(m.inAccountId, m.inAccountId)),
                span(nameById.getOrElse(m.outAccountId, m.outAccountId))
              )
            ),
            numCols = Set(1)
          )
        }
      )
    )
