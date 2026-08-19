package onparity.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import onparity.common.model.model.ID
import onparity.domain.model.DailySnapshot
import onparity.domain.port.driven.{DailySnapshotRepo, Txn}

import java.time.LocalDate
import java.util.UUID

class DailySnapshotRepoLive extends Repo[DailySnapshot, DailySnapshot, UUID] with DailySnapshotRepo:
  val d = DailySnapshot.Table

  def upsert(snapshot: DailySnapshot): Txn[Unit] =
    sql"""
      INSERT INTO $d ${d.insertColumns}
      VALUES ($snapshot)
      ON CONFLICT (fund_flow_id, snapshot_date) DO UPDATE SET
        match_rates_json               = EXCLUDED.match_rates_json,
        avg_match_rate_percent         = EXCLUDED.avg_match_rate_percent,
        rated_account_count            = EXCLUDED.rated_account_count,
        exceptions_by_category_json    = EXCLUDED.exceptions_by_category_json,
        exceptions_by_status_json      = EXCLUDED.exceptions_by_status_json,
        open_exception_count           = EXCLUDED.open_exception_count,
        aging_zero_to_three            = EXCLUDED.aging_zero_to_three,
        aging_four_to_seven            = EXCLUDED.aging_four_to_seven,
        aging_eight_to_fourteen        = EXCLUDED.aging_eight_to_fourteen,
        aging_fifteen_plus             = EXCLUDED.aging_fifteen_plus,
        overdue_count                  = EXCLUDED.overdue_count,
        updated_at                     = now()
    """.update.run()

  def findTrend(fundFlowId: ID, from: LocalDate, to: LocalDate): Txn[Vector[DailySnapshot]] =
    sql"""
      SELECT ${d.all}
      FROM $d AS a
      WHERE fund_flow_id = $fundFlowId
      AND snapshot_date BETWEEN $from AND $to
      ORDER BY snapshot_date
    """.query[DailySnapshot].run()
