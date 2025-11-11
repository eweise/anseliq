package com.eweise.perfectpair.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo}
import com.eweise.perfectpair.domain.model.Auditable

import java.time.OffsetDateTime

trait AuditableRepo[EC <: Auditable, E <: Auditable, ID] extends Repo[EC, E, ID]:
  override def insert(entityCreator: EC)(using DbCon): Unit =
    val now = Some(OffsetDateTime.now())
    entityCreator.createdAt = now
    entityCreator.updatedAt = now
    super.insert(entityCreator)

  override def update(entity: E)(using DbCon): Unit =
    entity.updatedAt = Some(OffsetDateTime.now())
    super.update(entity)
