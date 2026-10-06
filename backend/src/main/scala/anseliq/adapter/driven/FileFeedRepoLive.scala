package anseliq.adapter.driven

import anseliq.domain.model.FileFeed
import anseliq.domain.port.driven.FileFeedRepo
import com.augustnagro.magnum.Repo

import java.util.UUID

class FileFeedRepoLive extends Repo[FileFeed, FileFeed, UUID] with FileFeedRepo
