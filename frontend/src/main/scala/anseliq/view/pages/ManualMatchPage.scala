package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{
  ManualMatchRequest,
  MovementPayload,
  ReconRelationship,
  ReconciliationPayload,
  ReconciliationSpecPayload
}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.components.{Icons, PageHeader, Table}
import upickle.default.*

object ManualMatchPage:

  case class State(
    inMovements:        Seq[MovementPayload] = Seq.empty,
    outMovements:       Seq[MovementPayload] = Seq.empty,
    selectedIn:         Set[String]          = Set.empty,
    selectedOut:        Set[String]          = Set.empty,
    expandedProperties: Set[String]          = Set.empty,
    description:        String               = "Manual match",
    message:            Option[String]       = None
  )

  def apply(accountId: String, reconSpecId: String): HtmlElement =
    val spec         = Var(ReconciliationSpecPayload())
    val state        = Var(State())
    val loadBus      = EventBus[Unit]()
    val movementsBus = EventBus[ReconciliationSpecPayload]()
    val matchBus     = EventBus[Unit]()

    def loadReconSpec() =
      ApiClient.get[Seq[ReconciliationSpecPayload]](
        HttpPath.url(s"${HttpPath.account}/$accountId/recon-specs"),
        SessionStore.token
      )

    def loadMovements(s: ReconciliationSpecPayload) =
      ApiClient
        .get[Seq[MovementPayload]](
          HttpPath.url(s"${HttpPath.movements}/${s.inMovementSpecId}/unreconciled"),
          SessionStore.token
        )
        .combineWith(
          ApiClient.get[Seq[MovementPayload]](
            HttpPath.url(s"${HttpPath.movements}/${s.outMovementSpecId}/unreconciled"),
            SessionStore.token
          )
        )

    PageHeader.detailPage(
      loadBus.events.flatMapSwitch(_ => loadReconSpec()).apiBind { specs =>
        val found = specs.find(_.id.contains(reconSpecId)).getOrElse(ReconciliationSpecPayload())
        spec.set(found)
        movementsBus.emit(found)
      },
      movementsBus.events.flatMapSwitch(loadMovements) --> {
        case (Right(ins), Right(outs)) =>
          AppState.globalError.set(None)
          state.update(_.copy(inMovements = ins, outMovements = outs, selectedIn = Set.empty, selectedOut = Set.empty))
        case (Left(e), _) => AppState.globalError.set(Some(e))
        case (_, Left(e)) => AppState.globalError.set(Some(e))
      },
      matchBus.events.flatMapSwitch { _ =>
        val s = state.now()
        ApiClient.post[ReconciliationPayload, ManualMatchRequest](
          HttpPath.url(s"${HttpPath.reconSpec}/${spec.now().id.getOrElse("")}/manual-match"),
          ManualMatchRequest(
            inMovementIds  = s.selectedIn.toSeq,
            outMovementIds = s.selectedOut.toSeq,
            description    = s.description
          ),
          SessionStore.token
        )
      }.apiBind { recon =>
        state.update(_.copy(message = Some(s"Match saved as ${recon.status}")))
        movementsBus.emit(spec.now())
      },
      onMountCallback(_ => loadBus.emit(())),
      PageHeader.ledgerBreadcrumb(
        AppState.activeFundFlow.now(),
        Some("Reconciliations" -> "Reconciliations"),
        "Manual match" -> None
      ),
      PageHeader.title("Manual Match"),
      div(
        cls := "grid grid-cols-2 gap-4 mt-6 text-left",
        div(
          h3(cls := "font-medium mb-2 text-md-on-surface dark:text-white", "In Movements"),
          child <-- state.signal.map { s =>
            Table.create(
              Seq("", "Reference ID", "Amount"),
              s.inMovements.map(m =>
                Seq(
                  input(
                    typ := "checkbox",
                    checked <-- state.signal.map(_.selectedIn.contains(m.id)),
                    onClick.mapToUnit --> Observer[Unit](_ =>
                      state.update(st => st.copy(selectedIn = toggle(st.selectedIn, m.id)))
                    )
                  ),
                  referenceIdCell(m, state),
                  span(m.amount.toString)
                )
              ),
              numCols = Set(2)
            )
          }
        ),
        div(
          h3(cls := "font-medium mb-2 text-md-on-surface dark:text-white", "Out Movements"),
          child <-- state.signal.map { s =>
            Table.create(
              Seq("", "Reference ID", "Amount"),
              s.outMovements.map(m =>
                Seq(
                  input(
                    typ := "checkbox",
                    checked <-- state.signal.map(_.selectedOut.contains(m.id)),
                    onClick.mapToUnit --> Observer[Unit](_ =>
                      state.update(st => st.copy(selectedOut = toggle(st.selectedOut, m.id)))
                    )
                  ),
                  referenceIdCell(m, state),
                  span(m.amount.toString)
                )
              ),
              numCols = Set(2)
            )
          }
        )
      ),
      div(
        cls := "mt-4 text-sm text-md-on-surface dark:text-gray-200",
        child.text <-- state.signal.combineWith(spec.signal).map { (s, sp) =>
          val inTotal  = s.inMovements.filter(m => s.selectedIn.contains(m.id)).map(_.amount).sum
          val outTotal = s.outMovements.filter(m => s.selectedOut.contains(m.id)).map(_.amount).sum
          val diff     = inTotal - outTotal
          // Mirrors ManualReconciler.matchMovements: only a OneToMany group can still be completed
          // later, so only it is left InProgress; otherwise an unbalanced match is Mismatched.
          val note =
            if diff == BigDecimal(0) then "will close as Final"
            else if sp.relationship == ReconRelationship.OneToMany then "will save as InProgress"
            else "will save as Mismatched and raise an AmountMismatch dispute"
          s"Selected In: ${s.selectedIn.size} ($inTotal)  Selected Out: ${s.selectedOut.size} ($outTotal)  Diff: $diff — $note"
        }
      ),
      div(
        cls := "mt-4",
        child.maybe <-- state.signal.map(_.message.map(m => span(cls := "text-sm text-md-on-surface dark:text-gray-200", m)))
      ),
      div(
        cls := "mt-4",
        button(
          cls := "bg-md-primary text-md-on-primary rounded-full px-6 py-2.5 text-sm font-medium disabled:opacity-40",
          typ := "button",
          disabled <-- state.signal.map(s => s.selectedIn.isEmpty || s.selectedOut.isEmpty),
          onClick.mapToUnit --> Observer[Unit](_ => matchBus.emit(())),
          "Match Selected"
        )
      )
    )

  private def toggle(selected: Set[String], id: String): Set[String] =
    if selected.contains(id) then selected - id else selected + id

  private def referenceIdCell(m: MovementPayload, state: Var[State]): HtmlElement =
    div(
      div(
        cls := "flex items-center gap-1",
        button(
          cls := "cursor-pointer",
          typ := "button",
          onClick.mapToUnit --> Observer[Unit](_ =>
            state.update(st => st.copy(expandedProperties = toggle(st.expandedProperties, m.id)))
          ),
          child <-- state.signal.map(s =>
            if s.expandedProperties.contains(m.id) then Icons.minusCircle else Icons.plusCircle
          )
        ),
        span(m.referenceId)
      ),
      child <-- state.signal.map { s =>
        if s.expandedProperties.contains(m.id) then
          table(
            cls := "mt-1 text-xs border border-gray-200 dark:border-gray-700",
            tbody(
              m.properties.toSeq.sortBy(_._1).map { case (k, v) =>
                tr(
                  td(cls := "px-2 py-1 font-medium text-md-on-surface-variant dark:text-gray-400 border-b border-gray-100 dark:border-gray-700", k),
                  td(cls := "px-2 py-1 border-b border-gray-100 dark:border-gray-700", v)
                )
              }*
            )
          )
        else div()
      }
    )
