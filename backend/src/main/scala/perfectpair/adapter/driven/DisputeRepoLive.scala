package perfectpair.adapter.driven

import com.augustnagro.magnum.Repo
import perfectpair.domain.model.Dispute
import perfectpair.domain.port.driven.DisputeRepo

import java.util.UUID

class DisputeRepoLive extends Repo[Dispute, Dispute, UUID] with DisputeRepo
