package anseliq.adapter.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.{ExceptionCase, ExceptionCategory}
import anseliq.domain.port.driven.ExceptionRepo
import com.augustnagro.magnum.{DbCon, Repo, sql}

import java.util.UUID

class ExceptionRepoLive extends Repo[ExceptionCase, ExceptionCase, UUID] with ExceptionRepo:
  val d = ExceptionCase.Table

  def insertIfNoOpenException(exception: ExceptionCase)(using DbCon): Option[ExceptionCase] =
    sql"""
      INSERT INTO $d ${d.insertColumns}
      VALUES ($exception)
      ON CONFLICT (reference_id, category) WHERE status <> 'resolved' DO NOTHING
      RETURNING ${d.all}
    """.query[ExceptionCase].run().headOption

  def findByReferenceIdAndCategory(referenceId: ID, category: ExceptionCategory)(using DbCon): Vector[ExceptionCase] =
    sql"""
      SELECT ${d.all}
      FROM $d
      WHERE reference_id = $referenceId AND category = $category
      ORDER BY created_at
    """.query[ExceptionCase].run()

  def findOpenByReferenceId(referenceId: ID)(using DbCon): Vector[ExceptionCase] =
    sql"""
      SELECT ${d.all}
      FROM $d
      WHERE reference_id = $referenceId AND status <> 'resolved'
      ORDER BY created_at
    """.query[ExceptionCase].run()

  def findOpenByReferenceIdAndCategory(referenceId: ID, category: ExceptionCategory)(using
    DbCon
  ): Option[ExceptionCase] =
    sql"""
      SELECT ${d.all}
      FROM $d
      WHERE reference_id = $referenceId AND category = $category AND status <> 'resolved'
      LIMIT 1
    """.query[ExceptionCase].run().headOption
