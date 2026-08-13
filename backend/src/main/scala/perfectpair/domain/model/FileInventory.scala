package perfectpair.domain.model

import com.augustnagro.magnum.*
import perfectpair.common.model.model.ID

import java.time.OffsetDateTime
import java.util.UUID

enum FileInventoryStatus derives DbCodec:
  case QUEUED, PROCESSING, COMPLETED

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class FileInventory(
  @Id id:              ID           = createID(),
  movementSpecId:      ID,
  sourceSystem:        String,
  fileSourceEntryJson: String,
  fileSizeBytes:       Long,
  lastModifiedAt:      OffsetDateTime,
  status:              FileInventoryStatus,
  lastProcessedAt:     Option[OffsetDateTime],
  lastProcessedLine:   Option[Long] = None,
  updatedAt:           Option[OffsetDateTime]
) derives DbCodec:

  def fileSourceEntry(): FileSourceEntry = upickle.read(fileSourceEntryJson)

object FileInventory:
  val Table = TableInfo[FileInventory, FileInventory, UUID]
