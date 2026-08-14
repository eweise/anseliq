package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{MovementSpecPayload, ReconciliationSpecPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, Pill, Table}
import upickle.default.*

object ReconSpecsPage:

  case class State(
    reconSpecs:    Seq[ReconciliationSpecPayload] = Seq.empty,
    movementSpecs: Seq[MovementSpecPayload]       = Seq.empty
  )

  def apply(accountId: String): HtmlElement =
    val ffId  = AppState.activeFundFlow.now().flatMap(_.id).getOrElse("")
    val state = Var(State())

    div(
      ApiClient.get[Seq[ReconciliationSpecPayload]](
        HttpPath.url(s"${HttpPath.account}/$accountId/recon-specs"),
        SessionStore.token
      ) --> {
        case Right(specs) => state.update(_.copy(reconSpecs = specs))
        case Left(e)      => AppState.globalError.set(Some(e))
      },
      ApiClient.get[Seq[MovementSpecPayload]](
        HttpPath.url(s"${HttpPath.fundFlows}/$ffId/movement-specs"),
        SessionStore.token
      ) --> {
        case Right(specs) => state.update(_.copy(movementSpecs = specs))
        case Left(e)      => AppState.globalError.set(Some(e))
      },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center",
        Buttons.linkButton(
          "Add Recon Spec",
          Observer[Unit](_ => Nav.toMaintainReconSpec(accountId))
        ),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Aging Limit", "Relationship", ""),
            s.reconSpecs.map { spec =>
              Seq(
                span(spec.agingLimit.toString),
                Pill(spec.relationship.toString, Pill.Tone.Accent),
                div(
                  cls := "flex gap-2",
                  Buttons.textButton(
                    "Ingest Files",
                    Observer[Unit](_ => Nav.toIngest(accountId, spec.id.getOrElse("")))
                  ),
                  Buttons.textButton(
                    "Edit",
                    Observer[Unit](_ => Nav.toMaintainReconSpec(accountId, spec.id.getOrElse("")))
                  ),
                  Buttons.textButton(
                    "Reconciliations",
                    Observer[Unit](_ => Nav.toReconciliations(accountId, spec.id.getOrElse("")))
                  )
                )
              )
            },
            numCols = Set(0)
          )
        }
      )
    )
