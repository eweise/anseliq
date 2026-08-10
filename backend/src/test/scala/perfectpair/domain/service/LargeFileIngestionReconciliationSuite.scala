package perfectpair.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.adapter.driven.*
import perfectpair.common.model.{CSVParserConfig, DataSourceConfig}
import perfectpair.domain.DataSetup
import perfectpair.domain.model.*
import perfectpair.domain.port.driven.*
import perfectpair.testing.{LargeFileFixtures, rollBackOnly}

import java.io.{BufferedInputStream, FileInputStream}
import javax.sql.DataSource

// Excluded from the normal `sbt test` cycle in build.sbt — ingests and reconciles
// two 5,000,000-row files, which takes on the order of hours given Reconciler's
// current per-row (non-batched) matching loop. Run explicitly with:
//   sbt "backend/testOnly perfectpair.domain.service.LargeFileIngestionReconciliationSuite"
class LargeFileIngestionReconciliationSuite extends munit.FunSuite:
  given transactor: Transactor = Transactor(dataSource)

  given clockService: ClockService = ClockServiceLive()

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo: EventRepo = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()

  given fileSourceFactory: FileSourceFactory = S3FileSourceFactory()

  given ledger: Ledger = Ledger()

  private def csvSpec(spec: MovementSpec): MovementSpec =
    spec.copy(
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
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()

        val journalSpec = csvSpec(data.journalToRecon)
        val reportSpec  = csvSpec(data.reportToRecon)

        val journalStream = BufferedInputStream(FileInputStream(LargeFileFixtures.journalFile()), 1 << 20)
        try ingestor.ingest(journalSpec, InMemoryFileSourceEntry("journal-large"), journalStream)
        finally journalStream.close()

        val reportStream = BufferedInputStream(FileInputStream(LargeFileFixtures.reportFile()), 1 << 20)
        try ingestor.ingest(reportSpec, InMemoryFileSourceEntry("report-large"), reportStream)
        finally reportStream.close()

        val reconciler = Reconciler(
          accountRepo        = data.accountRepo,
          movementRepo       = data.movementRepo,
          movementSpecRepo   = data.movementSpecRepo,
          reconSpecRepo      = data.reconSpecRepo,
          reconRepo          = data.reconRepo,
          fundFlowConfigRepo = data.fundFlowConfigRepo
        )
        reconciler.reconcileAccount(data.reconAccount)

        val reconAccountAfter = accountRepo.findById(data.reconAccount.id).get
        assertEquals(reconAccountAfter.balance, BigDecimal(0), "reconAccount.balance")
        assertEquals(reconAccountAfter.unreconciledBalance, BigDecimal(0), "reconAccount.unreconciledBalance")
        assertEquals(
          movementRepo.countUnreconciledByAccount(data.reconAccount.id),
          0,
          "movements left unreconciled on the recon account"
        )
