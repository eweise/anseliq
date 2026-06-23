package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.common.model.S3ConnectionConfig
import perfectpair.domain.model.{FileInventoryStatus, S3FileSourceEntry}
import perfectpair.domain.port.driven.{AccountRepo, EventRepo, FileInventoryRepo, MovementRepo, MovementSpecRepo}
import perfectpair.domain.service.{ClockService, ClockServiceLive, EventStore, Ingestor}
import perfectpair.testing.rollBackOnly
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.net.URI
import java.util.UUID
import javax.sql.DataSource

class CreateFileInventoryTrackerS3Suite extends munit.FunSuite:

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

  given clockService:      ClockService      = ClockServiceLive()
  given transactor:        Transactor        = Transactor(dataSource)
  given dataSource:        DataSource        = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given eventRepo:         EventRepo         = EventRepoLive()
  given eventStore:        EventStore        = EventStore()
  given movementRepo:      MovementRepo      = MovementRepoLive()
  given accountRepo:       AccountRepo       = AccountRepoLive()
  given movementSpecRepo:  MovementSpecRepo  = MovementSpecRepoLive()
  given fileInventoryRepo: FileInventoryRepo = FileInventoryRepoLive()
  given fileSourceFactory: S3FileSourceFactory()

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
    transact(transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val ingestor       = Ingestor()
        val source         = S3FileSource(S3ConnectionConfig(bucket, "reports/"), s3Client)
        val result         = ingestor.detectAndQueue(movementSpecID, source)
        assertEquals(result.size, 3)
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
    transact(transactor):
      rollBackOnly:

        val ingestor = Ingestor()
        val source   = S3FileSource(S3ConnectionConfig(bucket, "idempotency/"), s3Client)
        val first    = ingestor.detectAndQueue(UUID.randomUUID(), source)
        val second   = ingestor.detectAndQueue(UUID.randomUUID(), source)
        assertEquals(first.size, 2)
        assert(second.isEmpty, "second run should not re-queue already tracked files")

  test("detectAndQueue picks up newly added S3 files on subsequent runs"):
    put("incremental/existing.csv")
    transact(transactor):
      rollBackOnly:
        val movementSpecID = UUID.randomUUID()
        val ingestor       = Ingestor()
        val source         = S3FileSource(S3ConnectionConfig(bucket, "incremental/"), s3Client)
        val first          = ingestor.detectAndQueue(movementSpecID, source)
        assertEquals(first.size, 1)
        put("incremental/new.csv")
        val second = ingestor.detectAndQueue(movementSpecID, source)
        assertEquals(second.size, 1)
        assertEquals(second.head.fileSourceEntry().asInstanceOf[S3FileSourceEntry].key, s"incremental/new.csv")
