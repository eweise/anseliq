package anseliq.adapter.driven

import com.augustnagro.magnum.Repo
import anseliq.domain.model.FileFeed
import anseliq.domain.port.driven.FileFeedRepo

import java.util.UUID

class FileFeedRepoLive extends Repo[FileFeed, FileFeed, UUID] with FileFeedRepo
