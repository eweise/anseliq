package anseliq.view.components

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{
  ReconciliationMovementPayload,
  ReconciliationPayload,
  ReverseWriteOffRequest,
  WriteOffRequest
}
import anseliq.http.ApiClient
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.pages.ReconciliationDetailPage
import upickle.default.*

/**
 * Everything a person can do to one reconciliation, driven by its status. The reconciliation detail
 * page's action bar and the exception page's subject card both render this, so resolving a mismatch
 * works the same from either place:
 *   - Mismatched / InProgress: Write off… and Unmatch
 *   - Final: Unmatch; or, for a written-off one, Reverse write-off (admins only — see
 *     ManualReconciler.reverseWriteOff), which puts it back to Mismatched
 *   - Voided / Reversed: nothing; a short note says why
 * Viewers get the note but none of the actions. Each action opens an inline panel to confirm, since
 * each changes the ledger. `onChanged` fires after either succeeds so the host page can reload.
 */
object ReconciliationActions:

  private enum Panel:
    case Closed, WriteOff, Unmatch, ReverseWriteOff

  private val panelCls =
    "flex flex-col gap-3 p-4 rounded-lg border border-md-outline dark:border-gray-700 bg-md-surface-variant dark:bg-md-surface-alt-dark max-w-xl"
  private val controlCls =
    "w-full px-3 py-2 text-sm rounded border border-md-outline dark:border-gray-600 bg-white dark:bg-gray-800 text-md-on-surface dark:text-gray-200"
  private val dangerBtnCls =
    "bg-md-critical text-white rounded-full px-5 py-2 text-sm font-medium tracking-wide disabled:opacity-40 disabled:pointer-events-none"

  def apply(
    r:          ReconciliationPayload,
    movements:  Seq[ReconciliationMovementPayload],
    onChanged:  Observer[Unit],
    agingLimit: Option[Int] = None
  ): HtmlElement =
    val panel       = Var(Panel.Closed)
    val writeOffBus = EventBus[(String, String)]() // (movement id, reason)
    val unmatchBus  = EventBus[Unit]()
    val reverseBus  = EventBus[String]()           // reason
    val writtenOff  = r.adjustmentReason.exists(_.nonEmpty)
    val isAdmin     = SessionStore.currentUserNow.exists(_.role == "Admin")

    val (offered, hint): (Seq[(String, Panel)], Option[String]) = r.status match
      case "Mismatched" =>
        Seq("Write off…" -> Panel.WriteOff, "Unmatch" -> Panel.Unmatch) ->
          Some(
            "Nothing else will join this match. Write off the difference, or unmatch to match these movements differently."
          )
      case "InProgress" =>
        Seq("Write off…" -> Panel.WriteOff, "Unmatch" -> Panel.Unmatch) ->
          Some(
            "Waiting for more movements to complete the group." +
              agingLimit.fold("")(d =>
                s" It's flagged as Mismatched if still unbalanced after $d day${if d == 1 then "" else "s"}."
              )
          )
      // A write-off is a deliberate ledger entry, so it isn't undone by a plain Unmatch: an admin
      // reverses it, which puts the reconciliation back to Mismatched (and from there it can be
      // unmatched like any other).
      case "Final" if writtenOff =>
        val reason = r.adjustmentReason.getOrElse("")
        if isAdmin then
          Seq("Reverse write-off…" -> Panel.ReverseWriteOff) ->
            Some(
              s"Written off ($reason). Reversing it posts a correcting entry and puts this reconciliation back to Mismatched."
            )
        else Seq.empty -> Some(s"Written off ($reason). Only an admin can reverse a write-off.")
      case "Final" => Seq("Unmatch" -> Panel.Unmatch) -> None
      case "Voided" =>
        Seq.empty -> Some("Voided: its movements were returned to the unreconciled pool. Nothing left to do here.")
      case "Reversed" =>
        Seq.empty -> Some("A system reversal pairing a movement with its correction. It can't be changed.")
      case _ => Seq.empty -> None
    // A Viewer still gets the hint, which says where the reconciliation stands, but no actions.
    val actions = if SessionStore.canEdit then offered else Seq.empty

    div(
      cls := "flex flex-col gap-3",
      writeOffBus.events.flatMapSwitch { (movementId, reason) =>
        ApiClient.postRaw(
          HttpPath.url(s"${HttpPath.reconciliation}/${r.id}/write-off"),
          write(WriteOffRequest(reason = reason, movementId = movementId)),
          SessionStore.token
        )
      }.apiBind { _ =>
        panel.set(Panel.Closed)
        onChanged.onNext(())
      },
      reverseBus.events.flatMapSwitch { reason =>
        ApiClient.postRaw(
          HttpPath.url(s"${HttpPath.reconciliation}/${r.id}/reverse-write-off"),
          write(ReverseWriteOffRequest(reason = reason)),
          SessionStore.token
        )
      }.apiBind { _ =>
        panel.set(Panel.Closed)
        onChanged.onNext(())
      },
      unmatchBus.events.flatMapSwitch { _ =>
        ApiClient.postRaw(HttpPath.url(s"${HttpPath.reconciliation}/${r.id}/unmatch"), "", SessionStore.token)
      }.apiBind { _ =>
        panel.set(Panel.Closed)
        onChanged.onNext(())
      },
      if actions.isEmpty then emptyNode
      else
        div(
          cls := "flex flex-wrap items-center gap-2",
          actions.zipWithIndex.map { case ((label, target), i) =>
            val styled =
              if i == 0 then
                "bg-md-primary text-md-on-primary rounded-full px-5 py-2 text-sm font-medium tracking-wide shadow-sm hover:bg-md-primary-hover"
              else
                "text-md-primary border border-md-primary rounded-full px-5 py-2 text-sm font-medium tracking-wide hover:bg-md-primary-container dark:hover:bg-md-primary-container-dark"
            button(typ := "button", cls := styled, onClick.mapTo(target) --> panel.writer, label)
          }
        )
      ,
      hint.map(h => p(cls := s"text-sm ${Form.mutedTextCls} max-w-2xl", h)).getOrElse(emptyNode),
      child <-- panel.signal.map {
        case Panel.Closed          => emptyNode
        case Panel.WriteOff        => writeOffPanel(r, movements, writeOffBus, panel)
        case Panel.Unmatch         => unmatchPanel(movements, unmatchBus, panel)
        case Panel.ReverseWriteOff => reverseWriteOffPanel(r, reverseBus, panel)
      }
    )

  // The chosen movement decides which accounts the difference posts between (see
  // ManualReconciler.writeOff): the adjustment runs between the reconciled account and that
  // movement's other account.
  private def writeOffPanel(
    r:           ReconciliationPayload,
    movements:   Seq[ReconciliationMovementPayload],
    writeOffBus: EventBus[(String, String)],
    panel:       Var[Panel]
  ): HtmlElement =
    val movementVar = Var("")
    val reasonVar   = Var("")
    div(
      cls := panelCls,
      div(cls := "font-medium text-md-on-surface dark:text-white", s"Write off ${r.balance}"),
      p(
        cls := s"text-sm ${Form.mutedTextCls}",
        "Posts the difference as an adjustment against the movement you choose, then closes this reconciliation as Final."
      ),
      label(
        forId := s"wo-movement-${r.id}",
        cls   := "text-xs font-medium text-md-on-surface-variant dark:text-gray-400",
        "Post against"
      ),
      select(
        idAttr := s"wo-movement-${r.id}",
        cls    := controlCls,
        onChange.mapToValue --> movementVar,
        option(value := "", "Choose a movement…"),
        movements.map(m => option(value := m.id, ReconciliationDetailPage.movementChoiceLabel(m)))
      ),
      label(
        forId := s"wo-reason-${r.id}",
        cls   := "text-xs font-medium text-md-on-surface-variant dark:text-gray-400",
        "Reason"
      ),
      input(
        idAttr      := s"wo-reason-${r.id}",
        typ         := "text",
        placeholder := "e.g. bank fee",
        cls         := controlCls,
        onInput.mapToValue --> reasonVar
      ),
      div(
        cls := "flex gap-2",
        button(
          typ := "button",
          cls := "bg-md-primary text-md-on-primary rounded-full px-5 py-2 text-sm font-medium tracking-wide disabled:opacity-40 disabled:pointer-events-none",
          disabled <-- movementVar.signal
            .combineWith(reasonVar.signal)
            .map((m, reason) => m.isEmpty || reason.trim.isEmpty),
          onClick.mapToUnit --> Observer[Unit](_ => writeOffBus.emit((movementVar.now(), reasonVar.now().trim))),
          "Write off"
        ),
        Buttons.textButton("Cancel", Observer[Unit](_ => panel.set(Panel.Closed)))
      )
    )

  private def unmatchPanel(
    movements:  Seq[ReconciliationMovementPayload],
    unmatchBus: EventBus[Unit],
    panel:      Var[Panel]
  ): HtmlElement =
    val n = movements.count(!_.movementType.contains("ManualAdjustment"))
    div(
      cls := panelCls,
      div(cls := "font-medium text-md-on-surface dark:text-white", "Unmatch this reconciliation?"),
      p(
        cls := s"text-sm ${Form.mutedTextCls}",
        s"Voids it and returns its $n movement${if n == 1 then "" else "s"} to the unreconciled pool, so they can be matched again." +
          " Any open mismatch exception is resolved."
      ),
      div(
        cls := "flex gap-2",
        button(typ := "button", cls := dangerBtnCls, onClick.mapToUnit --> unmatchBus, "Unmatch"),
        Buttons.textButton("Cancel", Observer[Unit](_ => panel.set(Panel.Closed)))
      )
    )

  private def reverseWriteOffPanel(
    r:          ReconciliationPayload,
    reverseBus: EventBus[String],
    panel:      Var[Panel]
  ): HtmlElement =
    val reasonVar = Var("")
    div(
      cls := panelCls,
      div(cls := "font-medium text-md-on-surface dark:text-white", "Reverse this write-off?"),
      p(
        cls := s"text-sm ${Form.mutedTextCls}",
        "Posts a correcting entry for the write-off adjustment (the original stays on the books), brings the difference back, " +
          "and puts this reconciliation back to Mismatched with a new exception. The match itself is kept."
      ),
      label(
        forId := s"rwo-reason-${r.id}",
        cls   := "text-xs font-medium text-md-on-surface-variant dark:text-gray-400",
        "Reason"
      ),
      input(
        idAttr      := s"rwo-reason-${r.id}",
        typ         := "text",
        placeholder := "e.g. fee was refunded",
        cls         := controlCls,
        onInput.mapToValue --> reasonVar
      ),
      div(
        cls := "flex gap-2",
        button(
          typ := "button",
          cls := dangerBtnCls,
          disabled <-- reasonVar.signal.map(_.trim.isEmpty),
          onClick.mapToUnit --> Observer[Unit](_ => reverseBus.emit(reasonVar.now().trim)),
          "Reverse write-off"
        ),
        Buttons.textButton("Cancel", Observer[Unit](_ => panel.set(Panel.Closed)))
      )
    )
