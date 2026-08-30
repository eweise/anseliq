package onparity.domain.fixture

import com.augustnagro.magnum.Transactor
import onparity.common.model.model.ID
import onparity.domain.model.{FileInventory, FileSourceEntry}
import onparity.domain.port.driven.*
import onparity.domain.service.{ClockService, EventStore, Ingestor, Ledger}

// Not `val`-marked: Ingestor already exposes each of these as an inherited given member (it takes
// the identical `using val` clause itself), so redeclaring them as members here would need
// `override` to shadow the inherited one. Plain `using` parameters still satisfy Ingestor's own
// constructor implicitly and are still referenceable by name (e.g. `clock` below) within this
// class's body — they just aren't re-exposed as ControlTimeIngestor's own members.
class ControlTimeIngestor(using
  ledger:             Ledger,
  movementRepo:       MovementRepo,
  movementSpecRepo:   MovementSpecRepo,
  fileFeedRepo:       FileFeedRepo,
  eventStore:         EventStore,
  fileInventoryRepo:  FileInventoryRepo,
  fileSourceFactory:  FileSourceFactory,
  disputeRepo:        DisputeRepo,
  disputeCommentRepo: DisputeCommentRepo,
  clock:              ClockService,
  transactor:         Transactor
) extends Ingestor:

  override def createFileInventory(fileFeedID: ID, source: FileSource, newFile: FileSourceEntry): FileInventory =
    val fi      = super.createFileInventory(fileFeedID, source, newFile)
    val updated = fi.copy(lastModifiedAt = clock.now)
    updated
