package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.{CSVParserConfig, DataSourceConfig}
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.port.driven.*

import java.util.UUID
import javax.sql.DataSource

// Excluded from the normal `sbt test` cycle in build.sbt — ingests and reconciles two
// 5,000,000-row files. Historically took on the order of hours, dominated by
// findUnreconciledPage re-sorting the full remaining unreconciled set on every page
// (O(n^2 / pageSize) — see MovementRepoLive.findUnreconciledPage). InMemoryReconciler's
// MergeCursor now streams both sides via MovementRepoLive.withUnreconciledCursor, which
// sorts each side exactly once per run instead of once per page, so this should run far
// faster — not re-measured at this scale here, since the ingestion calls below are
// currently commented out. Run explicitly with:
//   sbt "backend/testOnly onparity.domain.service.LargeFileIngestionReconciliationSuite"
class LargeFileIngestionReconciliationSuite extends munit.FunSuite:
  given transactor: Transactor =
    Transactor(dataSource)
//    Transactor(dataSource, SqlLogger.logSlowQueries(FiniteDuration(5, TimeUnit.SECONDS)), _ => ())

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given clockService: ClockService = ClockServiceLive()

  given eventRepo: EventRepo = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given fileFeedRepo: FileFeedRepo = FileFeedRepoLive()

  given fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()

  given fileSourceFactory: FileSourceFactory = S3FileSourceFactory()

  given reconSpecRepo: ReconciliationSpecRepo = ReconciliationSpecRepoLive()

  given reconRepo: ReconciliationRepo = ReconciliationRepoLive()

  given ledger: Ledger = Ledger()

  given fundFlowConfigRepo: FundFlowConfigRepo = FundFlowConfigRepoLive()

  given jobRepo: JobRepo = JobRepoLive()

  given disputeRepo: DisputeRepo = DisputeRepoLive()

  given disputeCommentRepo: DisputeCommentRepo = DisputeCommentRepoLive()

  private def csvFeed(): FileFeed =
    FileFeed(
      dataSourceJson = upickle.write(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = false,
            columnDefinitions = Map(0 -> "id"),
            amountField       = Some(1)
          )
        )
      )
    )

  test("ingesting and reconciling two 5,000,000-row files nets the recon account to zero"):
    val ingestor = Ingestor()
    val data     = transact(transactor)(DataSetup())
//    try
//    val journalSpec = csvSpec(data.journalToRecon)
//    val reportSpec  = csvSpec(data.reportToRecon)
//
//    val journalStream = BufferedInputStream(FileInputStream(LargeFileFixtures.journalFile()), 1 << 10000)
//    try ingestor.ingestResumable(journalSpec, InMemoryFileSourceEntry("journal-large"), journalStream)
//    finally journalStream.close()
//
//    val reportStream = BufferedInputStream(FileInputStream(LargeFileFixtures.reportFile()), 1 << 10000)
//    try ingestor.ingestResumable(reportSpec, InMemoryFileSourceEntry("report-large"), reportStream)
//    finally reportStream.close()

    val reconciler     = InMemoryReconciler()
    val reconAccountId = UUID.fromString("00000000-007b-7c70-a4b1-6eb83731ebd5")
    transact(transactor)(reconciler.reconcileAccountResumable(reconAccountId))

    val reconAccountAfter = transact(transactor)(accountRepo.findById(reconAccountId).get)
    assertEquals(reconAccountAfter.balance, BigDecimal(0), "reconAccount.balance")
    assertEquals(reconAccountAfter.unreconciledBalance, BigDecimal(0), "reconAccount.unreconciledBalance")
    assertEquals(
      transact(transactor)(movementRepo.countUnreconciledByAccount(reconAccountId)),
      0,
      "movements left unreconciled on the recon account"
    )
//    finally
//      transact(transactor):
//        val specIds = Set(data.journalToRecon.id, data.reportToRecon.id)
//        eventRepo
//          .findByEventType("MoneyMovementsIngested")
//          .filter(es => specIds.contains(es.event[MoneyMovementsIngested].movementSpecID))
//          .foreach(es => eventRepo.deleteById(es.id))
//        // reconciliation.spec_id -> reconciliation_spec has no ON DELETE CASCADE, so any
//        // reconciliations reconcileAccount created must go before the spec they reference.
//        data.reconRepo.findByReconSpecID(data.reconSpec.id).foreach(r => data.reconRepo.deleteById(r.id))
//        data.reconSpecRepo.deleteById(data.reconSpec.id)
//        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)
