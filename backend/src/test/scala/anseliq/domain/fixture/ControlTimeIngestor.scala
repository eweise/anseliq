package anseliq.domain.fixture

import anseliq.adapter.config.AppContainer
import anseliq.common.model.model.ID
import anseliq.domain.model.{FileInventory, FileSourceEntry}
import anseliq.domain.port.driven.*
import anseliq.domain.service.Ingestor

// An Ingestor wired from `app` that stamps each new file's lastModifiedAt with app's clock, so a
// test (or ControlTimeMain) that moves the clock forward controls when files appear to have landed.
class ControlTimeIngestor(app: AppContainer)
    extends Ingestor(
      ledger            = app.ledger,
      movementRepo      = app.movementRepo,
      movementSpecRepo  = app.movementSpecRepo,
      fileFeedRepo      = app.fileFeedRepo,
      eventStore        = app.eventStore,
      fileInventoryRepo = app.fileInventoryRepo,
      fileSourceFactory = app.fileSourceFactory,
      disputeService    = app.disputeService,
      clock             = app.clock,
      transactor        = app.transactor
    ):

  override def createFileInventory(fileFeedID: ID, source: FileSource, newFile: FileSourceEntry): FileInventory =
    val fi      = super.createFileInventory(fileFeedID, source, newFile)
    val updated = fi.copy(lastModifiedAt = clock.now)
    updated
