package perfectpair.view

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object ViewHelper:
  private val customFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def formatDateTime(dt: OffsetDateTime): String =
    dt.format(customFormatter)
