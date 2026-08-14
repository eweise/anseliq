package onparity.domain.service

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import onparity.adapter.driven.*
import onparity.common.model.{CSVParserConfig, DataSourceConfig}
import onparity.domain.DataSetup
import onparity.domain.model.*
import onparity.domain.port.driven.*
import onparity.testing.rollBackOnly

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import javax.sql.DataSource

class IngestorSuite extends munit.FunSuite:
  given transactor:   Transactor   = Transactor(dataSource)

  given clockService: ClockService = ClockServiceLive()

  given dataSource:   DataSource   = DbDataSource.create(ConfigFactory.load("test-application.conf"))

  given eventRepo:    EventRepo    = EventRepoLive()

  given eventStore: EventStore = EventStore()

  given movementRepo: MovementRepo = MovementRepoLive()

  given accountRepo: AccountRepo = AccountRepoLive()

  given movementSpecRepo: MovementSpecRepo = MovementSpecRepoLive()

  given fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()

  given fileSourceFactory: FileSourceFactory = S3FileSourceFactory()

  given ledger: Ledger = Ledger()

  def stubSource(entries: Vector[FileSourceEntry], system: String = "S3") = new FileSource:
    val sourceSystem = system
    def listFiles(): Vector[FileSourceEntry] = entries
    override def getFile(entry: FileSourceEntry): InputStream = ???

  private def createExistingFileInventory(movementSpecID: UUID) =
    val s3Entry = S3FileSourceEntry(
      bucket         = "bucket",
      key            = "existing",
      fileSizeBytes  = 512L,
      lastModifiedAt = clockService.now
    )
    FileInventory(
      sourceSystem        = "S3",
      movementSpecId      = movementSpecID,
      fileSourceEntryJson = upickle.write(s3Entry),
      fileSizeBytes       = 512L,
      lastModifiedAt      = clockService.now,
      status              = FileInventoryStatus.QUEUED,
      lastProcessedAt     = None,
      updatedAt           = None
    )


  test("Post Amount balances"):
    val ingestor = Ingestor()
    transact(transactor):
      rollBackOnly:
        val data: DataSetup = DataSetup()
        val is = ByteArrayInputStream("""value1, value2, value3
                                        |value4, value5, value6""".stripMargin.getBytes(UTF_8))
        ingestor.ingest(data.journalToRecon, InMemoryFileSourceEntry("test"), is)
        val allMovements = movementRepo.findAll
        assert(allMovements.exists(_.properties.props.exists((key, value) => key == "field1" && value == "value1")))

  test("detectAndQueue inserts new files with QUEUED status"):
    transact(transactor):
      rollBackOnly:
        val ingestor = Ingestor()
        val entries = Vector(
          S3FileSourceEntry(bucket = "bucket", key = "file1.csv", 1024L, clockService.now),
          S3FileSourceEntry(bucket = "bucket", key = "file2.csv", 2048L, clockService.now)
        )
        val result = ingestor.detectAndQueue(UUID.randomUUID(), stubSource(entries))
        assertEquals(result.size, 2)
        assert(result.forall(_.status == FileInventoryStatus.QUEUED))

  test("detectAndQueue skips files already in the inventory"):
    transact(transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val ingestor       = Ingestor()
        val existing       = createExistingFileInventory(movementSpecID)
        fileInventoryRepo.insert(existing)
        val entries = Vector(
          S3FileSourceEntry(bucket = "bucket", key = "existing", 512L, clockService.now),
          S3FileSourceEntry(bucket = "bucket", key = "new", 1024L, clockService.now)
        )
        val result = ingestor.detectAndQueue(movementSpecID, stubSource(entries))
        assertEquals(result.size, 1)
        val foundKey = result.head.fileSourceEntry().asInstanceOf[S3FileSourceEntry].key
        assertEquals(foundKey, "new")

  test("detectAndQueue returns empty when all source files are already in inventory"):
    transact(transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val ingestor       = Ingestor()
        fileInventoryRepo.insert(createExistingFileInventory(movementSpecID))
        val result = ingestor.detectAndQueue(
          movementSpecID,
          stubSource(Vector(S3FileSourceEntry("bucket", "existing", 512L, clockService.now)))
        )
        assert(result.isEmpty)

  private def movementSpecWithAmountField(spec: onparity.domain.model.MovementSpec) =
    spec.copy(
      dataSourceJson = upickle.write(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = false,
            columnDefinitions = Map(1 -> "field1"),
            amountField       = Some(0)
          )
        )
      )
    )

  test("ingest advances account balance and unreconciledBalance by the movement amount"):
    val ingestor = Ingestor()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        data.reconAccount.balance   = BigDecimal(500)
        data.journalAccount.balance = BigDecimal(300)
        accountRepo.update(data.reconAccount)
        accountRepo.update(data.journalAccount)
        val spec = movementSpecWithAmountField(data.journalToRecon)
        val is   = ByteArrayInputStream("100,rowA".getBytes(UTF_8))
        ingestor.ingest(spec, InMemoryFileSourceEntry("test"), is)

        val updatedInAccount  = accountRepo.findById(data.reconAccount.id).get
        val updatedOutAccount = accountRepo.findById(data.journalAccount.id).get
        assertEquals(updatedInAccount.balance, BigDecimal(600), "inAccount.balance")
        assertEquals(updatedInAccount.unreconciledBalance, BigDecimal(100), "inAccount.unreconciledBalance")
        assertEquals(updatedOutAccount.balance, BigDecimal(200), "outAccount.balance")
        assertEquals(updatedOutAccount.unreconciledBalance, BigDecimal(-100), "outAccount.unreconciledBalance")

        val movement = movementRepo.findAll.head
        assertEquals(movement.inAccountBalance, BigDecimal(600))
        assertEquals(movement.outAccountBalance, BigDecimal(200))

  test("ingest computes a running balance per movement across a batch"):
    val ingestor = Ingestor()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        data.reconAccount.balance   = BigDecimal(500)
        data.journalAccount.balance = BigDecimal(300)
        accountRepo.update(data.reconAccount)
        accountRepo.update(data.journalAccount)
        val spec = movementSpecWithAmountField(data.journalToRecon)
        val is = ByteArrayInputStream(
          """100,rowA
            |50,rowB
            |25,rowC""".stripMargin.getBytes(UTF_8)
        )
        ingestor.ingest(spec, InMemoryFileSourceEntry("test"), is)

        val movements = movementRepo.findAll
        assertEquals(movements.size, 3)

        def byAmount(amount: BigDecimal) = movements.find(_.amount == amount).get

        assertEquals(byAmount(100).inAccountBalance, BigDecimal(600))
        assertEquals(byAmount(100).outAccountBalance, BigDecimal(200))
        assertEquals(byAmount(50).inAccountBalance, BigDecimal(650))
        assertEquals(byAmount(50).outAccountBalance, BigDecimal(150))
        assertEquals(byAmount(25).inAccountBalance, BigDecimal(675))
        assertEquals(byAmount(25).outAccountBalance, BigDecimal(125))

        val updatedInAccount  = accountRepo.findById(data.reconAccount.id).get
        val updatedOutAccount = accountRepo.findById(data.journalAccount.id).get
        assertEquals(updatedInAccount.balance, BigDecimal(675))
        assertEquals(updatedInAccount.unreconciledBalance, BigDecimal(175))
        assertEquals(updatedOutAccount.balance, BigDecimal(125))
        assertEquals(updatedOutAccount.unreconciledBalance, BigDecimal(-175))

  test("ingest sets inAccountBalance and outAccountBalance from account balances"):
    val ingestor = Ingestor()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        // journalToRecon: inAccountId = reconAccount, outAccountId = journalAccount
        data.reconAccount.balance   = BigDecimal(500)
        data.journalAccount.balance = BigDecimal(300)
        accountRepo.update(data.reconAccount)
        accountRepo.update(data.journalAccount)
        val is = ByteArrayInputStream("value1, value2, value3".getBytes(UTF_8))
        ingestor.ingest(data.journalToRecon, InMemoryFileSourceEntry("test"), is)
        val movements = movementRepo.findAll
        assert(movements.nonEmpty)
        assert(movements.forall(_.inAccountBalance == BigDecimal(500)))
        assert(movements.forall(_.outAccountBalance == BigDecimal(300)))

  test("ingest captures same balance snapshot for all movements in a batch"):
    val ingestor = Ingestor()
    transact(transactor):
      rollBackOnly:
        val data = DataSetup()
        data.reconAccount.balance   = BigDecimal(1000)
        data.journalAccount.balance = BigDecimal(200)
        accountRepo.update(data.reconAccount)
        accountRepo.update(data.journalAccount)
        val is = ByteArrayInputStream(
          """row1, b, c
            |row2, e, f
            |row3, h, i""".stripMargin.getBytes(UTF_8)
        )
        ingestor.ingest(data.journalToRecon, InMemoryFileSourceEntry("test"), is)
        val movements = movementRepo.findAll
        assertEquals(movements.size, 3)
        assert(movements.forall(_.inAccountBalance == BigDecimal(1000)))
        assert(movements.forall(_.outAccountBalance == BigDecimal(200)))
