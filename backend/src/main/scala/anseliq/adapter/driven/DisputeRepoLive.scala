package anseliq.adapter.driven

import anseliq.common.model.model.ID
import anseliq.domain.model.{Dispute, DisputeCategory}
import anseliq.domain.port.driven.DisputeRepo
import com.augustnagro.magnum.{DbCon, Repo, sql}

import java.util.UUID

class DisputeRepoLive extends Repo[Dispute, Dispute, UUID] with DisputeRepo:
  val d = Dispute.Table

  def insertIfNoOpenDispute(dispute: Dispute)(using DbCon): Option[Dispute] =
    sql"""
      INSERT INTO $d ${d.insertColumns}
      VALUES ($dispute)
      ON CONFLICT (reference_id, category) WHERE status <> 'resolved' DO NOTHING
      RETURNING ${d.all}
    """.query[Dispute].run().headOption

  def findByReferenceIdAndCategory(referenceId: ID, category: DisputeCategory)(using DbCon): Vector[Dispute] =
    sql"""
      SELECT ${d.all}
      FROM $d
      WHERE reference_id = $referenceId AND category = $category
      ORDER BY created_at
    """.query[Dispute].run()

  def findOpenByReferenceId(referenceId: ID)(using DbCon): Vector[Dispute] =
    sql"""
      SELECT ${d.all}
      FROM $d
      WHERE reference_id = $referenceId AND status <> 'resolved'
      ORDER BY created_at
    """.query[Dispute].run()

  def findOpenByReferenceIdAndCategory(referenceId: ID, category: DisputeCategory)(using DbCon): Option[Dispute] =
    sql"""
      SELECT ${d.all}
      FROM $d
      WHERE reference_id = $referenceId AND category = $category AND status <> 'resolved'
      LIMIT 1
    """.query[Dispute].run().headOption
