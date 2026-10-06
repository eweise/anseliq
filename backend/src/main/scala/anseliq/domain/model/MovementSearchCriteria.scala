package anseliq.domain.model

import anseliq.common.model.model.{ID, Money}

import java.time.OffsetDateTime

// OpenBalance is every movement still contributing to an account's balance: unreconciled ones plus
// those in an InProgress or Mismatched reconciliation (Final reconciliations net to zero, and a
// write-off makes its reconciliation Final). For a recon account, these sum to its cash balance.
enum ReconciliationFilter:
  case All, Unreconciled, OpenBalance

case class MovementSearchCriteria(
  fundFlowId:     ID,
  accountId:      Option[ID]          = None,
  amount:         Option[Money]       = None,
  movementSpecId: Option[ID]          = None,
  properties:     Map[String, String] = Map.empty,
  // Substring match against the whole file_source_entry value ("$bucket|$key") — not split into
  // bucket/key separately. The bucket is effectively constant per movement spec in practice, so
  // asking the user to type it every search is redundant; a single filename fragment matched
  // anywhere in the stored value (which usually falls within the key/path portion) is what they
  // actually want to search by.
  fileSourceQuery: Option[String]         = None,
  reconciliation:  ReconciliationFilter   = ReconciliationFilter.All,
  createdFrom:     Option[OffsetDateTime] = None,
  createdTo:       Option[OffsetDateTime] = None,
  movementType:    Option[MovementType]   = None,
  // Narrows ManualAdjustment movements to one purpose, e.g. only write-offs.
  adjustmentType: Option[AdjustmentType] = None
)
