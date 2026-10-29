package anseliq.adapter.driven

import anseliq.domain.service.ClockService
import anseliq.domain.model.FileFeed
import anseliq.domain.port.driven.FileFeedRepo

import java.util.UUID

class FileFeedRepoLive(clock: ClockService) extends AuditedRepo[FileFeed, UUID](clock) with FileFeedRepo
