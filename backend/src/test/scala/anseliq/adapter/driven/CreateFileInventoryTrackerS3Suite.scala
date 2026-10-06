package anseliq.adapter.driven

import anseliq.common.model.{CSVParserConfig, DataSourceConfig, S3ConnectionConfig}
import anseliq.domain.DataSetup
import anseliq.domain.model.*
import anseliq.domain.service.*
import anseliq.testing.{TestAppContainer, rollBackOnly}
import com.augustnagro.magnum.transact
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.net.URI
import java.util.UUID

class CreateFileInventoryTrackerS3Suite extends munit.FunSuite:
  private val app = TestAppContainer()

  lazy val s3Client: S3Client = S3Client
    .builder()
    .region(Region.US_EAST_1)
    .endpointOverride(localstackEndpoint)
    .credentialsProvider(
      StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
    )
    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
    .build()
  val localstackEndpoint = URI.create("http://localhost:4566")
  val bucket             = "file-inventory-test"

  override def beforeAll(): Unit =
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())

  override def afterAll(): Unit =
    emptyBucket()
    s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build())
    s3Client.close()

  private def emptyBucket(): Unit =
    val resp = s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
    resp
      .contents()
      .forEach(obj => s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build()))

  private def put(key: String, content: String = "col1,col2\n1,2"): Unit =
    s3Client.putObject(
      PutObjectRequest.builder().bucket(bucket).key(key).build(),
      RequestBody.fromString(content)
    )

  test("detectAndQueue queues all S3 files not yet in inventory"):
    put("reports/jan.csv")
    put("reports/feb.csv")
    put("reports/mar.csv")
    transact(app.transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val ingestor       = app.ingestor
        val source         = S3FileSource(S3ConnectionConfig(bucket, "reports/"), s3Client)
        val result         = ingestor.detectAndQueue(movementSpecID, source)
        assertEquals(result.size, 3, s"results from movementSpecID ${movementSpecID}")
        assert(result.forall(_.status == FileInventoryStatus.QUEUED))
        assert(result.forall(_.sourceSystem == "S3"))
        assertEquals(
          result.map(_.fileSourceEntry().asInstanceOf[S3FileSourceEntry].key).toSet,
          Set(
            s"reports/jan.csv",
            s"reports/feb.csv",
            s"reports/mar.csv"
          )
        )

  test("detectAndQueue is idempotent — second run skips already-queued files"):
    put("idempotency/a.csv")
    put("idempotency/b.csv")
    transact(app.transactor):
      rollBackOnly:

        val ingestor = app.ingestor
        val source   = S3FileSource(S3ConnectionConfig(bucket, "idempotency/"), s3Client)
        val first    = ingestor.detectAndQueue(UUID.randomUUID(), source)
        val second   = ingestor.detectAndQueue(UUID.randomUUID(), source)
        assertEquals(first.size, 2)
        assert(second.isEmpty, "second run should not re-queue already tracked files")

  test("detectAndQueue picks up newly added S3 files on subsequent runs"):
    put("incremental/existing.csv")
    transact(app.transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val ingestor       = app.ingestor
        val source         = S3FileSource(S3ConnectionConfig(bucket, "incremental/"), s3Client)
        val first          = ingestor.detectAndQueue(movementSpecID, source)
        assertEquals(first.size, 1)
        put("incremental/new.csv")
        val second = ingestor.detectAndQueue(movementSpecID, source)
        assertEquals(second.size, 1)
        assertEquals(second.head.fileSourceEntry().asInstanceOf[S3FileSourceEntry].key, s"incremental/new.csv")

  private def csvFeed(): FileFeed =
    FileFeed(
      dataSourceJson = upickle.write(
        DataSourceConfig(
          parserConfig = CSVParserConfig(
            hasHeader         = false,
            columnDefinitions = Map(0 -> "id"),
            amountField       = Some(1)
          ),
          // ingestFileFromInventory (unlike ingest/ingestResumable called directly) actually calls
          // app.fileSourceFactory.createFileSource(connectionConfig) to fetch the file, and
          // DataSourceConfig.connectionConfig defaults to S3ConnectionConfig("", "") (empty bucket) —
          // must point at the real test bucket or the fetch fails against a nonexistent bucket.
          connectionConfig = S3ConnectionConfig(bucket, "resume-test/")
        )
      )
    )

  test("ingestFileFromInventory resumes from the persisted checkpoint and marks COMPLETED only once finished"):
    put("resume-test/journal.csv", "1,10\n2,20\n3,30\n4,40\n5,50")

    val data = transact(app.transactor)(DataSetup(app))
    val feed = csvFeed()

    transact(app.transactor):
      app.fileFeedRepo.insert(feed)
      app.movementSpecRepo.update(data.journalToRecon.copy(fileFeedId = feed.id))

    val s3Entry = S3FileSourceEntry(
      bucket         = bucket,
      key            = "resume-test/journal.csv",
      fileSizeBytes  = 0L,
      lastModifiedAt = app.clock.now
    )
    val fileInventory = FileInventory(
      fileFeedId          = feed.id,
      sourceSystem        = "S3",
      fileSourceEntryJson = upickle.write(s3Entry),
      fileSizeBytes       = 0L,
      lastModifiedAt      = app.clock.now,
      status              = FileInventoryStatus.QUEUED,
      lastProcessedAt     = None,
      updatedAt           = None
    )
    transact(app.transactor)(app.fileInventoryRepo.insert(fileInventory))

    try
      val ingestor = app.ingestor

      val first = ingestor.ingestFileFromInventory(
        fileInventory.id,
        batchSize = 2,
        onBatchComplete =
          progress => if progress.lastLineInBatch == 4 then BatchDecision.Rollback else BatchDecision.Commit
      )
      assertEquals(first.completed, false)
      assertEquals(first.recordCount, 4)

      val afterFirst = transact(app.transactor)(app.fileInventoryRepo.findById(fileInventory.id).get)
      assertEquals(afterFirst.status, FileInventoryStatus.PROCESSING)
      assertEquals(afterFirst.lastProcessedLine, Some(3L))

      val second = ingestor.ingestFileFromInventory(fileInventory.id)
      assertEquals(second.completed, true)
      assertEquals(second.recordCount, 1, "only the previously-uncommitted 5th row")

      val afterSecond = transact(app.transactor)(app.fileInventoryRepo.findById(fileInventory.id).get)
      assertEquals(afterSecond.status, FileInventoryStatus.COMPLETED)

      val allMovements = transact(app.transactor)(app.movementRepo.findByAccount(data.reconAccount.id))
      assertEquals(allMovements.size, 5)
      assertEquals(allMovements.map(_.amount).sum, BigDecimal(10 + 20 + 30 + 40 + 50))
    finally
      transact(app.transactor):
        val specIds = Set(data.journalToRecon.id, data.reportToRecon.id)
        app.eventRepo
          .findByEventType("MoneyMovementsIngested")
          .filter(es => specIds.contains(es.event[MoneyMovementsIngested].movementSpecID))
          .foreach(es => app.eventRepo.deleteById(es.id))
        app.fileInventoryRepo.deleteById(fileInventory.id)
        data.reconSpecRepo.deleteById(data.reconSpec.id)
        data.fundFlowConfigRepo.deleteById(data.fundFlowConfig.id)
