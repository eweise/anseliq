package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{
  AccountPayload,
  ManualMatchRequest,
  MatchSuggestionPayload,
  MovementPayload,
  MovementSpecPayload,
  ReconRelationship,
  ReconciliationPayload,
  ReconciliationSpecPayload
}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.ViewHelper
import anseliq.view.components.{Icons, PageHeader, Table}
import upickle.default.*

object ManualMatchPage:

  case class State(
    inMovements:        Seq[MovementPayload] = Seq.empty,
    outMovements:       Seq[MovementPayload] = Seq.empty,
    selectedIn:         Set[String]          = Set.empty,
    selectedOut:        Set[String]          = Set.empty,
    expandedProperties: Set[String]          = Set.empty,
    suggestions:        Seq[MatchSuggestionPayload] = Seq.empty,
    description:        String               = "Manual match",
    message:            Option[String]       = None
  )

  def apply(accountId: String, reconSpecId: String): HtmlElement =
    val spec         = Var(ReconciliationSpecPayload())
    val state        = Var(State())
    val loadBus      = EventBus[Unit]()
    val movementsBus = EventBus[ReconciliationSpecPayload]()
    val matchBus     = EventBus[Unit]()
    val acceptBus    = EventBus[String]()
    val rejectBus    = EventBus[String]()
    // The ledger's accounts and movement specs, only to name each side after its account.
    val accounts      = Var(Seq.empty[AccountPayload])
    val movementSpecs = Var(Seq.empty[MovementSpecPayload])
    val ffId          = AppState.activeFundFlow.now().flatMap(_.id).getOrElse("")
    val primaryName = spec.signal.combineWith(movementSpecs.signal, accounts.signal).map { (sp, specs, accts) =>
      ViewHelper.primarySideName(sp.inMovementSpecId, specs, accts)
    }
    val secondaryName = spec.signal.combineWith(movementSpecs.signal, accounts.signal).map { (sp, specs, accts) =>
      ViewHelper.secondarySideName(sp.outMovementSpecId, specs, accts)
    }

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
      // Pending suggestions are reloaded with the movements, so they stay in step after any match.
      movementsBus.events
        .flatMapSwitch(s =>
          ApiClient.get[Seq[MatchSuggestionPayload]](
            HttpPath.url(s"${HttpPath.reconSpec}/${s.id.getOrElse("")}/match-suggestions"),
            SessionStore.token
          )
        )
        .apiBind(suggestions => state.update(_.copy(suggestions = suggestions))),
      acceptBus.events
        .flatMapSwitch(id =>
          ApiClient.postEmpty[ReconciliationPayload](HttpPath.url(s"${HttpPath.matchSuggestion}/$id/accept"), SessionStore.token)
        )
        .apiBind { recon =>
          state.update(_.copy(message = Some(s"Suggestion accepted: match saved as ${recon.status}")))
          movementsBus.emit(spec.now())
        },
      rejectBus.events
        .flatMapSwitch(id => ApiClient.postRaw(HttpPath.url(s"${HttpPath.matchSuggestion}/$id/reject"), "", SessionStore.token))
        .apiBind { _ =>
          state.update(_.copy(message = Some("Suggestion rejected")))
          movementsBus.emit(spec.now())
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
      ApiClient
        .get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token)
        .apiBind(accounts.set),
      ApiClient
        .get[Seq[MovementSpecPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/movement-specs"), SessionStore.token)
        .apiBind(movementSpecs.set),
      PageHeader.ledgerBreadcrumb(
        AppState.activeFundFlow.now(),
        Some("Reconciliations" -> "Reconciliations"),
        "Manual match" -> None
      ),
      PageHeader.title("Manual Match"),
      child <-- state.signal.combineWith(primaryName, secondaryName).map { (s, pName, sName) =>
        suggestionsSection(s, pName, sName, acceptBus, rejectBus)
      },
      div(
        cls := "grid grid-cols-2 gap-4 mt-6 text-left",
        div(
          h3(cls := "font-medium mb-2 text-md-on-surface dark:text-white", child.text <-- primaryName.map(n => s"$n movements")),
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
          h3(cls := "font-medium mb-2 text-md-on-surface dark:text-white", child.text <-- secondaryName.map(n => s"$n movements")),
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
        child.text <-- state.signal.combineWith(spec.signal, primaryName, secondaryName).map { (s, sp, pName, sName) =>
          val inTotal  = s.inMovements.filter(m => s.selectedIn.contains(m.id)).map(_.amount).sum
          val outTotal = s.outMovements.filter(m => s.selectedOut.contains(m.id)).map(_.amount).sum
          val diff     = inTotal - outTotal
          // Mirrors ManualReconciler.matchMovements: only a OneToMany group can still be completed
          // later, so only it is left InProgress; otherwise an unbalanced match is Mismatched.
          val note =
            if diff == BigDecimal(0) then "will close as Final"
            else if sp.relationship == ReconRelationship.OneToMany then "will save as InProgress"
            else "will save as Mismatched and raise an AmountMismatch dispute"
          s"$pName: ${s.selectedIn.size} selected ($inTotal)  $sName: ${s.selectedOut.size} selected ($outTotal)  Difference: $diff — $note"
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

  // Suggestions from secondary matching for what the spec's rule left unmatched. Accepting one
  // matches its movements exactly as "Match Selected" would.
  private def suggestionsSection(
    s:         State,
    pName:     String,
    sName:     String,
    acceptBus: EventBus[String],
    rejectBus: EventBus[String]
  ): HtmlElement =
    if s.suggestions.isEmpty then div()
    else
      val movements = (s.inMovements ++ s.outMovements).map(m => m.id -> m).toMap
      def describe(id: String): HtmlElement =
        movements.get(id).fold(span(id))(m => div(span(m.referenceId), span(cls := "ml-2 text-md-on-surface-variant dark:text-gray-400", m.amount.toString)))
      div(
        cls := "mt-6 text-left",
        h3(cls := "font-medium mb-2 text-md-on-surface dark:text-white", "Suggested matches"),
        Table.create(
          Seq(pName, sName, "Confidence", "Reason", ""),
          s.suggestions.map(sg =>
            Seq(
              describe(sg.movementId),
              div(sg.suggestedMovementIds.map(describe)*),
              span(s"${(sg.confidence * 100).setScale(0, BigDecimal.RoundingMode.HALF_UP)}%"),
              div(
                span(sg.rationale),
                sg.explanation.map(e => div(cls := "font-mono text-xs text-md-on-surface dark:text-gray-200", s"Checked: $e")),
                div(cls := "text-xs text-md-on-surface-variant dark:text-gray-400", sg.model)
              ),
              div(
                cls := "flex gap-2",
                button(
                  cls := "bg-md-primary text-md-on-primary rounded-full px-4 py-1.5 text-xs font-medium",
                  typ := "button",
                  onClick.mapToUnit --> Observer[Unit](_ => acceptBus.emit(sg.id)),
                  "Accept"
                ),
                button(
                  cls := "border border-gray-300 dark:border-gray-600 text-md-on-surface dark:text-gray-200 rounded-full px-4 py-1.5 text-xs font-medium",
                  typ := "button",
                  onClick.mapToUnit --> Observer[Unit](_ => rejectBus.emit(sg.id)),
                  "Reject"
                )
              )
            )
          ),
          numCols = Set(2)
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
