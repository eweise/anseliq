package anseliq.adapter.driven

import anseliq.domain.model.Audited
import anseliq.domain.service.ClockService
import com.augustnagro.magnum.{BatchUpdateResult, DbCon, Repo, RepoDefaults}

// A Repo that stamps createdAt/updatedAt from `clock` on every write, so a test that moves the clock
// forward sees rows created and updated at the moved time. An insert keeps a createdAt the caller
// already set; an update leaves createdAt alone (it was loaded with the row), and the column's
// NOT NULL constraint catches an update of an entity that never had one.
//
// A subclass that overrides a write method with its own SQL has to stamp the entity itself, with
// stampCreated/stampUpdated.
open class AuditedRepo[E <: Audited, ID](clock: ClockService)(using RepoDefaults[E, E, ID]) extends Repo[E, E, ID]:

  protected def stampCreated(entity: E): E =
    val now = clock.now
    if entity.createdAt.isEmpty then entity.createdAt = Some(now)
    entity.updatedAt                                  = Some(now)
    entity

  protected def stampUpdated(entity: E): E =
    entity.updatedAt = Some(clock.now)
    entity

  override def insert(entity: E)(using DbCon): Unit = super.insert(stampCreated(entity))

  override def insertAll(entities: Iterable[E])(using DbCon): Unit = super.insertAll(entities.map(stampCreated))

  override def insertReturning(entity: E)(using DbCon): E = super.insertReturning(stampCreated(entity))

  override def insertAllReturning(entities: Iterable[E])(using DbCon): Vector[E] =
    super.insertAllReturning(entities.map(stampCreated))

  override def update(entity: E)(using DbCon): Unit = super.update(stampUpdated(entity))

  override def updateAll(entities: Iterable[E])(using DbCon): BatchUpdateResult =
    super.updateAll(entities.map(stampUpdated))
