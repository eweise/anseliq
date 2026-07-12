package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{ManualMatchRequest, MovementPayload, ReconciliationPayload, ReconciliationSpecPayload}
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.{Buttons, Table}
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

  def apply(accountId: String): HtmlElement =
    val spec   = AppState.editReconSpec.now().getOrElse(ReconciliationSpecPayload())
    val specId = spec.id.getOrElse("")
    val state  = Var(State())
    val loadBus  = EventBus[Unit]()
    val matchBus = EventBus[Unit]()

    def loadMovements() =
      ApiClient.get[Seq[MovementPayload]](
        HttpPath.url(s"${HttpPath.movements}/${spec.inMovementSpecId}/unreconciled"),
        SessionStore.token
      ).combineWith(
        ApiClient.get[Seq[MovementPayload]](
          HttpPath.url(s"${HttpPath.movements}/${spec.outMovementSpecId}/unreconciled"),
          SessionStore.token
        )
      )

    div(
      loadBus.events.flatMapSwitch(_ => loadMovements()) --> {
        case (Right(ins), Right(outs)) =>
          state.update(_.copy(inMovements = ins, outMovements = outs, selectedIn = Set.empty, selectedOut = Set.empty))
        case (Left(e), _) => AppState.globalError.set(Some(e))
        case (_, Left(e)) => AppState.globalError.set(Some(e))
      },
      matchBus.events.flatMapSwitch { _ =>
        val s = state.now()
        ApiClient.post[ReconciliationPayload, ManualMatchRequest](
          HttpPath.url(s"${HttpPath.reconSpec}/$specId/manual-match"),
          ManualMatchRequest(
            inMovementIds  = s.selectedIn.toSeq,
            outMovementIds = s.selectedOut.toSeq,
            description    = s.description
          ),
          SessionStore.token
        )
      } --> {
        case Right(recon) =>
          state.update(_.copy(message = Some(s"Match saved as ${recon.status}")))
          loadBus.emit(())
        case Left(e) => AppState.globalError.set(Some(e))
      },
      onMountCallback { _ => loadBus.emit(()) },
      cls := "flex flex-col items-center mb-8 pt-20",
      div(
        cls := "my-8 text-center w-full max-w-4xl",
        h2(cls := "text-2xl font-medium mb-6", "Manual Match"),
        Buttons.linkButton("Back", Observer[Unit](_ => Nav.toReconciliations(accountId))),
        div(
          cls := "grid grid-cols-2 gap-4 mt-6 text-left",
          div(
            h3(cls := "font-medium mb-2", "In Movements"),
            child <-- state.signal.map { s =>
              Table.create(
                Seq("", "Reference ID", "Amount"),
                s.inMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedIn.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedIn = toggle(st.selectedIn, m.id))))
                    ),
                    referenceIdCell(m, state),
                    span(m.amount.toString)
                  )
                )
              )
            }
          ),
          div(
            h3(cls := "font-medium mb-2", "Out Movements"),
            child <-- state.signal.map { s =>
              Table.create(
                Seq("", "Reference ID", "Amount"),
                s.outMovements.map(m =>
                  Seq(
                    input(
                      typ := "checkbox",
                      checked <-- state.signal.map(_.selectedOut.contains(m.id)),
                      onClick.mapToUnit --> Observer[Unit](_ => state.update(st => st.copy(selectedOut = toggle(st.selectedOut, m.id))))
                    ),
                    referenceIdCell(m, state),
                    span(m.amount.toString)
                  )
                )
              )
            }
          )
        ),
        div(
          cls := "mt-4 text-sm",
          child.text <-- state.signal.map { s =>
            val inTotal  = s.inMovements.filter(m => s.selectedIn.contains(m.id)).map(_.amount).sum
            val outTotal = s.outMovements.filter(m => s.selectedOut.contains(m.id)).map(_.amount).sum
            val diff     = inTotal - outTotal
            val note     = if diff == BigDecimal(0) then "will close as Final" else "will save as InProgress"
            s"Selected In: ${s.selectedIn.size} ($inTotal)  Selected Out: ${s.selectedOut.size} ($outTotal)  Diff: $diff — $note"
          }
        ),
        div(
          cls := "mt-4",
          child.maybe <-- state.signal.map(_.message.map(m => span(cls := "text-sm", m)))
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
    )

  private def toggle(selected: Set[String], id: String): Set[String] =
    if selected.contains(id) then selected - id else selected + id

  private def referenceIdCell(m: MovementPayload, state: Var[State]): HtmlElement =
    div(
      div(
        cls := "flex items-center gap-1",
        button(
          cls := "text-xs w-4 text-md-primary hover:underline",
          typ := "button",
          onClick.mapToUnit --> Observer[Unit](_ =>
            state.update(st => st.copy(expandedProperties = toggle(st.expandedProperties, m.id)))
          ),
          child.text <-- state.signal.map(s => if s.expandedProperties.contains(m.id) then "−" else "+")
        ),
        span(m.referenceId)
      ),
      child <-- state.signal.map { s =>
        if s.expandedProperties.contains(m.id) then
          table(
            cls := "mt-1 text-xs border border-gray-200",
            tbody(
              m.properties.toSeq.sortBy(_._1).map { case (k, v) =>
                tr(
                  td(cls := "px-2 py-1 font-medium border-b border-gray-100", k),
                  td(cls := "px-2 py-1 border-b border-gray-100", v)
                )
              }*
            )
          )
        else div()
      }
    )
