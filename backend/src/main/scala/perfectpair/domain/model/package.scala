package perfectpair.domain.model

import java.util.UUID

def createID(): UUID =
  val uuid = java.util.UUID.ofEpochMillis(123)
  uuid
