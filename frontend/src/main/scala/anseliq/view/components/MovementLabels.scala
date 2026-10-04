package anseliq.view.components

import anseliq.common.model.AdjustmentTypePayload

// How a movement's type reads to a person. A ManualAdjustment is named by what it's for (its
// adjustmentType, e.g. a write-off) when that's known, rather than every adjustment being assumed
// to be a write-off; one without an adjustmentType is just a manual adjustment.
object MovementLabels:

  def typeLabel(movementType: Option[String], adjustmentType: Option[String]): String =
    adjustmentType.flatMap(a => AdjustmentTypePayload.values.find(_.toString == a)) match
      case Some(a) => a.label
      case None =>
        movementType match
          case Some("ManualAdjustment") => "Manual adjustment"
          case Some(other)              => other
          case None                     => "—"
