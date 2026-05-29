package perfectpair.domain.port.driven

import perfectpair.domain.model.FileSourceEntry

import java.time.OffsetDateTime


trait FileSource:
  def sourceSystem: String
  def listFiles():  Vector[FileSourceEntry]
