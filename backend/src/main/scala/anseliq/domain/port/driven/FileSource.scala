package anseliq.domain.port.driven

import anseliq.common.model.ConnectionConfig
import anseliq.domain.model.FileSourceEntry

import java.io.InputStream
import java.time.OffsetDateTime

trait FileSourceFactory:
  def createFileSource(conn: ConnectionConfig): FileSource

trait FileSource:
  def sourceSystem: String
  def listFiles():  Vector[FileSourceEntry]
  def getFile(entry: FileSourceEntry): InputStream
