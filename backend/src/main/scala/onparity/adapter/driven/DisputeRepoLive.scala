package onparity.adapter.driven

import com.augustnagro.magnum.{DbCon, Repo, sql}
import onparity.domain.model.Dispute
import onparity.domain.port.driven.DisputeRepo

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
