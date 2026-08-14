package onparity.domain.port.driven

import com.augustnagro.magnum.{BatchUpdateResult, DbCon}
import onparity.common.model.model.ID

trait DomainRepo[E, EC] {
  def count(using DbCon): Long

  def existsById(id: ID)(using DbCon): Boolean

  def findAll(using DbCon): Vector[E]

  def findById(id: ID)(using DbCon): Option[E]

  def findAllById(ids: Iterable[ID])(using DbCon): Vector[E]

  def delete(entity: E)(using DbCon): Unit

  def deleteById(id: ID)(using DbCon): Unit

  def truncate()(using DbCon): Unit

  def deleteAll(entities: Iterable[E])(using DbCon): BatchUpdateResult

  def deleteAllById(ids: Iterable[ID])(using DbCon): BatchUpdateResult

  def insert(entityCreator: EC)(using DbCon): Unit

  def insertAll(entityCreators: Iterable[EC])(using DbCon): Unit

  def insertReturning(entityCreator: EC)(using DbCon): E

  def insertAllReturning(entityCreators: Iterable[EC])(using DbCon): Vector[E]

  def update(entity: E)(using DbCon): Unit

  def updateAll(entities: Iterable[E])(using DbCon): BatchUpdateResult

}
