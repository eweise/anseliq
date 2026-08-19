package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.model.ID

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class DailySnapshot(
  @Id id:                      ID                     = createID(),
  fundFlowId:                  ID,
  snapshotDate:                LocalDate,
  matchRatesJson:              String                 = "[]",
  avgMatchRatePercent:         Option[BigDecimal]     = None,
  ratedAccountCount:           Int                    = 0,
  exceptionsByCategoryJson:    String                 = "{}",
  exceptionsByStatusJson:      String                 = "{}",
  openExceptionCount:          Int                    = 0,
  agingZeroToThree:            Int                    = 0,
  agingFourToSeven:            Int                    = 0,
  agingEightToFourteen:        Int                    = 0,
  agingFifteenPlus:            Int                    = 0,
  overdueCount:                Int                    = 0,
  var createdAt:               Option[OffsetDateTime] = None,
  var updatedAt:               Option[OffsetDateTime] = None
) derives DbCodec

object DailySnapshot:
  val Table = TableInfo[DailySnapshot, DailySnapshot, UUID]
