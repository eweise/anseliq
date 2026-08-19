package onparity.domain.port.driven

import onparity.common.model.model.ID
import onparity.domain.model.DailySnapshot

import java.time.LocalDate

trait DailySnapshotRepo extends DomainRepo[DailySnapshot, DailySnapshot]:

  // Idempotent: re-running the snapshot job for the same (fundFlowId, snapshotDate) overwrites
  // that day's row instead of erroring or duplicating.
  def upsert(snapshot: DailySnapshot): Txn[Unit]

  def findTrend(fundFlowId: ID, from: LocalDate, to: LocalDate): Txn[Vector[DailySnapshot]]
