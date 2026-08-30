package onparity.domain.model

import onparity.common.model.model.{ID, Money}

import java.time.OffsetDateTime

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
  fileSourceQuery:  Option[String]         = None,
  unreconciledOnly: Boolean                = false,
  createdFrom:      Option[OffsetDateTime] = None,
  createdTo:        Option[OffsetDateTime] = None
)
