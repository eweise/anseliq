package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import com.augustnagro.magnum.{BatchUpdateResult, DbCon}

trait DomainRepo[E, EC] {
  def count(using DbCon): Long

  def findAll(using DbCon): Vector[E]

  def findById(id: ID)(using DbCon): Option[E]

  def delete(entity: E)(using DbCon): Unit

  def deleteById(id: ID)(using DbCon): Unit

  def truncate()(using DbCon): Unit

  def insert(entityCreator: EC)(using DbCon): Unit

  def insertAll(entityCreators: Iterable[EC])(using DbCon): Unit

  def insertReturning(entityCreator: EC)(using DbCon): E

  def insertAllReturning(entityCreators: Iterable[EC])(using DbCon): Vector[E]

  def update(entity: E)(using DbCon): Unit

  def updateAll(entities: Iterable[E])(using DbCon): BatchUpdateResult

}
