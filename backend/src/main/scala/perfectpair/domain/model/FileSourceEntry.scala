package perfectpair.domain.model

import java.time.OffsetDateTime
import upickle.ReadWriter
import perfectpair.common.model.OffsetDateTimeRW.rw

sealed trait FileSourceEntry derives ReadWriter:
  def fileSizeBytes:  Long
  def lastModifiedAt: OffsetDateTime

case class S3FileSourceEntry(
  bucket:         String,
  key:            String,
  fileSizeBytes:  Long,
  lastModifiedAt: OffsetDateTime
) extends FileSourceEntry
