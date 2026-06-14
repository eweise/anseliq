package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{MovementSpecPayload, ReconciliationSpecPayload}
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.{Buttons, Table}
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
          Observer[Unit](_ =>
            AppState.editReconSpec.set(None)
            Nav.toMaintainReconSpec(accountId)
          )
        ),
        child <-- state.signal.map { s =>
          Table.create(
            Seq("Aging Limit", "Relationship", ""),
            s.reconSpecs.map { spec =>
              Seq(
                span(spec.agingLimit.toString),
                span(spec.relationship.toString),
                div(
                  cls := "flex gap-2",
                  Buttons.textButton(
                    "Ingest Files",
                    Observer[Unit](_ =>
                      AppState.editReconSpec.set(Some(spec))
                      Nav.toIngest(accountId)
                    )
                  ),
                  Buttons.textButton(
                    "Edit",
                    Observer[Unit](_ =>
                      AppState.editReconSpec.set(Some(spec))
                      Nav.toMaintainReconSpec(accountId)
                    )
                  ),
                  Buttons.textButton(
                    "Reconciliations",
                    Observer[Unit](_ =>
                      AppState.editReconSpec.set(Some(spec))
                      Nav.toReconciliations(accountId)
                    )
                  )
                )
              )
            }
          )
        }
      )
    )
