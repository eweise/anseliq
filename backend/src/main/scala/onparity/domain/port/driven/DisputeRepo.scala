package onparity.domain.port.driven

import com.augustnagro.magnum.DbCon
import onparity.common.model.model.ID
import onparity.domain.model.{Dispute, DisputeCategory}

trait DisputeRepo extends DomainRepo[Dispute, Dispute]:
  /**
   * Inserts unless an open (non-resolved) dispute already exists for this (referenceId, category)
   * pair — see the dispute_reference_id_category_open_unique index. Returns None on conflict.
   */
  def insertIfNoOpenDispute(dispute: Dispute)(using DbCon): Option[Dispute]

  def findOpenByReferenceIdAndCategory(referenceId: ID, category: DisputeCategory)(using DbCon): Option[Dispute]

  // Every non-resolved dispute against one target, any category, oldest first.
  def findOpenByReferenceId(referenceId: ID)(using DbCon): Vector[Dispute]
