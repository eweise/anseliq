package anseliq.domain.port.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.{ExceptionCase, ExceptionCategory}
import com.augustnagro.magnum.DbCon

trait ExceptionRepo extends DomainRepo[ExceptionCase, ExceptionCase]:
  /**
   * Inserts unless an open (non-resolved) exception already exists for this (referenceId, category)
   * pair — see the exception_reference_id_category_open_unique index. Returns None on conflict.
   */
  def insertIfNoOpenException(exception: ExceptionCase)(using DbCon): Option[ExceptionCase]

  def findOpenByReferenceIdAndCategory(referenceId: ID, category: ExceptionCategory)(using DbCon): Option[ExceptionCase]

  // Every exception of one category against one target, any status, oldest first.
  def findByReferenceIdAndCategory(referenceId: ID, category: ExceptionCategory)(using DbCon): Vector[ExceptionCase]

  // Every non-resolved exception against one target, any category, oldest first.
  def findOpenByReferenceId(referenceId: ID)(using DbCon): Vector[ExceptionCase]
