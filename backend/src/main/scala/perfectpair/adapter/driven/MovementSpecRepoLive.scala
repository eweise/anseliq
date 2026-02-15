package perfectpair.adapter.driven

import com.augustnagro.magnum.Repo
import perfectpair.domain.model
import perfectpair.domain.model.MovementSpec
import perfectpair.domain.port.driven.MovementSpecRepo

import java.util.UUID

class MovementSpecRepoLive extends Repo[MovementSpec, MovementSpec, UUID] with MovementSpecRepo
