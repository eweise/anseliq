package onparity.adapter.driven

import com.augustnagro.magnum.Repo
import onparity.domain.model.Dispute
import onparity.domain.port.driven.DisputeRepo

import java.util.UUID

class DisputeRepoLive extends Repo[Dispute, Dispute, UUID] with DisputeRepo
