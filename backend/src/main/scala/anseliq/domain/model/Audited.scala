package anseliq.domain.model

import java.time.OffsetDateTime

// An entity whose createdAt/updatedAt the repo stamps from ClockService on every write (see
// AuditedRepo), so they follow the app's clock rather than the database's.
trait Audited:
  var createdAt: Option[OffsetDateTime]
  var updatedAt: Option[OffsetDateTime]
