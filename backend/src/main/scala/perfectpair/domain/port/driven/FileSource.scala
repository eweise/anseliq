package perfectpair.domain.port.driven

import java.time.OffsetDateTime

case class FileSourceEntry(
  filePath:       String,
  fileSizeBytes:  Long,
  lastModifiedAt: OffsetDateTime
)

trait FileSource:
  def sourceSystem: String
  def listFiles(): Vector[FileSourceEntry]
