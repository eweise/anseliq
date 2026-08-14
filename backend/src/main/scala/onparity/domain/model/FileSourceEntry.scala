package onparity.domain.model

import onparity.common.model.OffsetDateTimeRW.rw
import upickle.ReadWriter

import java.time.OffsetDateTime

sealed trait FileSourceEntry derives ReadWriter:
  def fileSizeBytes:  Long
  def lastModifiedAt: OffsetDateTime
  def name:           String

case class S3FileSourceEntry(
  bucket:         String,
  key:            String,
  fileSizeBytes:  Long,
  lastModifiedAt: OffsetDateTime
) extends FileSourceEntry:
  def name: String = s"$bucket|$key"

case class InMemoryFileSourceEntry(
  name:           String,
  fileSizeBytes:  Long           = 0,
  lastModifiedAt: OffsetDateTime = OffsetDateTime.now()
) extends FileSourceEntry
