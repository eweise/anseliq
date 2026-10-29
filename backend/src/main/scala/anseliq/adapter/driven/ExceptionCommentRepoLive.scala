package anseliq.adapter.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.ExceptionComment
import anseliq.domain.port.driven.{ExceptionCommentRepo, Txn}
import com.augustnagro.magnum.{Repo, sql}

import java.util.UUID

class ExceptionCommentRepoLive extends Repo[ExceptionComment, ExceptionComment, UUID] with ExceptionCommentRepo:
  val c = ExceptionComment.Table

  def findByExceptionId(exceptionId: ID): Txn[Vector[ExceptionComment]] =
    sql"""
      SELECT ${c.all}
      FROM $c AS a
      WHERE exception_id = $exceptionId
      ORDER BY created_at
    """.query[ExceptionComment].run()
