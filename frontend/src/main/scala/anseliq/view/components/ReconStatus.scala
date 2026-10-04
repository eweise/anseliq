package anseliq.view.components

import com.raquo.laminar.api.L.*
import anseliq.common.model.ReconciliationPayload

// One rendering of a reconciliation's status everywhere it appears (lists, detail page, exception
// page). A Final reconciliation with an adjustment reason reads "Written Off", since that's how
// people think of it even though its stored status is Final.
object ReconStatus:

  // ReconciliationStatus names in the order the ledger's filter chips show them.
  val filters: Seq[(String, String)] = Seq(
    "Mismatched" -> "Mismatched",
    "InProgress" -> "In progress",
    "Final"      -> "Final",
    "Voided"     -> "Voided",
    "Reversed"   -> "Reversed"
  )

  def pill(r: ReconciliationPayload): HtmlElement =
    val (label, tone) = r.status match
      case "InProgress"                                     => "In Progress" -> Pill.Tone.Warning
      case "Mismatched"                                     => "Mismatched"  -> Pill.Tone.Critical
      case "Final" if r.adjustmentReason.exists(_.nonEmpty) => "Written Off" -> Pill.Tone.Serious
      case "Final"                                          => "Final"       -> Pill.Tone.Good
      case "Voided"                                         => "Voided"      -> Pill.Tone.Neutral
      case "Reversed"                                       => "Reversed"    -> Pill.Tone.Neutral
      case other                                            => other         -> Pill.Tone.Neutral
    Pill(label, tone)
