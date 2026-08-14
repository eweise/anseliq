package onparity.domain.port.driven

import onparity.common.model.ConnectionConfig
import onparity.domain.model.FileSourceEntry

import java.io.InputStream
import java.time.OffsetDateTime

trait FileSourceFactory:
  def createFileSource(conn: ConnectionConfig): FileSource

trait FileSource:
  def sourceSystem: String
  def listFiles():  Vector[FileSourceEntry]
  def getFile(entry: FileSourceEntry): InputStream
