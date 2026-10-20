package anseliq.domain.model

import anseliq.common.model.model.ID
import com.augustnagro.magnum.*

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class DailySnapshot(
  @Id id:         ID     = createID(),
  fundFlowId:     ID,
  snapshotDate:   LocalDate,
  matchRatesJson: String = "[]",
  // Renamed in place from avgMatchRatePercent (same physical column, ALTER TABLE RENAME COLUMN) —
  // must stay in this exact position, not reordered, to match the column's physical position.
  avgBalanceCoveragePercent: Option[BigDecimal]     = None,
  ratedAccountCount:         Int                    = 0,
  exceptionsByCategoryJson:  String                 = "{}",
  exceptionsByStatusJson:    String                 = "{}",
  openExceptionCount:        Int                    = 0,
  agingZeroToThree:          Int                    = 0,
  agingFourToSeven:          Int                    = 0,
  agingEightToFourteen:      Int                    = 0,
  agingFifteenPlus:          Int                    = 0,
  overdueCount:              Int                    = 0,
  var createdAt:             Option[OffsetDateTime] = None,
  var updatedAt:             Option[OffsetDateTime] = None,
  // Must stay last: ALTER TABLE appends new columns physically, and Magnum maps DbCodec fields
  // positionally, so a new field has to be added at the end of the case class to match.
  avgMatchRatePercent:     Option[BigDecimal] = None,
  reconciledMovementCount: Int                = 0,
  totalMovementCount:      Int                = 0
) extends Audited derives DbCodec

object DailySnapshot:
  val Table = TableInfo[DailySnapshot, DailySnapshot, UUID]
