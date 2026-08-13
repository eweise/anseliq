package perfectpair.adapter.driven

import com.augustnagro.magnum.{Repo, sql}
import perfectpair.common.model.model.ID
import perfectpair.domain.model.DisputeComment
import perfectpair.domain.port.driven.{DisputeCommentRepo, Txn}

import java.util.UUID

class DisputeCommentRepoLive extends Repo[DisputeComment, DisputeComment, UUID] with DisputeCommentRepo:
  val c = DisputeComment.Table

  def findByDisputeId(disputeId: ID): Txn[Vector[DisputeComment]] =
    sql"""
      SELECT ${c.all}
      FROM $c AS a
      WHERE dispute_id = $disputeId
      ORDER BY created_at
    """.query[DisputeComment].run()
