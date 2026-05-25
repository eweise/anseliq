package perfectpair.domain.model

import com.augustnagro.magnum.{DbCodec, Id, PostgresDbType, SqlNameMapper, Table, TableInfo}
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

enum FileInventoryStatus derives DbCodec:
  case QUEUED, PROCESSING, COMPLETED, FAILED

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FileInventory(
  @Id id:          ID = createID(),
  sourceSystem:    String,
  filePath:        String,
  fileSizeBytes:   Long,
  lastModifiedAt:  OffsetDateTime,
  status:          FileInventoryStatus,
  lastProcessedAt: Option[OffsetDateTime],
  updatedAt:       Option[OffsetDateTime]
) derives DbCodec

object FileInventory:
  val Table = TableInfo[FileInventory, FileInventory, UUID]
