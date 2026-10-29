package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.DailySnapshot

import java.time.LocalDate

trait DailySnapshotRepo extends DomainRepo[DailySnapshot, DailySnapshot]:

  // Idempotent: re-running the snapshot job for the same (ledgerId, snapshotDate) overwrites
  // that day's row instead of erroring or duplicating.
  def upsert(snapshot: DailySnapshot): Txn[Unit]

  def findTrend(ledgerId: ID, from: LocalDate, to: LocalDate): Txn[Vector[DailySnapshot]]

  def existsForDate(ledgerId: ID, date: LocalDate): Txn[Boolean]
