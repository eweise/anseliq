package onparity.adapter.driven

import com.augustnagro.magnum.Repo
import onparity.domain.model.FileFeed
import onparity.domain.port.driven.FileFeedRepo

import java.util.UUID

class FileFeedRepoLive extends Repo[FileFeed, FileFeed, UUID] with FileFeedRepo
