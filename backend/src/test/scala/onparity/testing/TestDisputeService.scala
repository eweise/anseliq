package onparity.testing

import onparity.adapter.driven.{DisputeCommentRepoLive, DisputeRepoLive, UserRepoLive}
import onparity.domain.port.driven.*
import onparity.domain.service.{ClockService, DisputeService, EventStore}

/**
 * InMemoryReconciler, ManualReconciler and Ingestor take a DisputeService for the disputes they
 * raise and resolve on their own. Most suites only have the repos it needs inside each test (via
 * `import data.given` from DataSetup), so rather than building one by hand in every test, a suite
 * does `import onparity.testing.TestDisputeService.given` and gets a DisputeService assembled from
 * whatever repos are in scope where it's needed. The defaults cover the repos most fixtures don't
 * provide.
 */
object TestDisputeService:
  given disputeServiceFromScope(using
    movementRepo:       MovementRepo,
    reconRepo:          ReconciliationRepo,
    movementSpecRepo:   MovementSpecRepo,
    fileFeedRepo:       FileFeedRepo,
    eventStore:         EventStore,
    clock:              ClockService,
    disputeRepo:        DisputeRepo = DisputeRepoLive(),
    disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive(),
    userRepo:           UserRepo = UserRepoLive()
  ): DisputeService = DisputeService()
