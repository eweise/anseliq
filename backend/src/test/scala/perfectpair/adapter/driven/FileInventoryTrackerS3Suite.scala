package perfectpair.adapter.driven

import com.augustnagro.magnum.{Transactor, transact}
import com.typesafe.config.ConfigFactory
import perfectpair.domain.model.FileInventoryStatus
import perfectpair.domain.service.FileInventoryTracker
import perfectpair.testing.rollBackOnly
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.{
  CreateBucketRequest, DeleteObjectRequest, DeleteBucketRequest, ListObjectsV2Request, PutObjectRequest
}

import java.net.URI
import javax.sql.DataSource

class FileInventoryTrackerS3Suite extends munit.FunSuite:

  val localstackEndpoint = URI.create("http://localhost:4566")
  val bucket             = "file-inventory-test"

  given dataSource: DataSource = DbDataSource.create(ConfigFactory.load("test-application.conf"))
  given transactor: Transactor = Transactor(dataSource)

  lazy val s3Client: S3Client = S3Client.builder()
    .region(Region.US_EAST_1)
    .endpointOverride(localstackEndpoint)
    .credentialsProvider(
      StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
    )
    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
    .build()

  override def beforeAll(): Unit =
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())

  override def afterAll(): Unit =
    emptyBucket()
    s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build())
    s3Client.close()

  private def put(key: String, content: String = "col1,col2\n1,2"): Unit =
    s3Client.putObject(
      PutObjectRequest.builder().bucket(bucket).key(key).build(),
      RequestBody.fromString(content)
    )

  private def emptyBucket(): Unit =
    val resp = s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
    resp.contents().forEach(obj =>
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build())
    )

  test("detectAndQueue queues all S3 files not yet in inventory"):
    put("reports/jan.csv")
    put("reports/feb.csv")
    put("reports/mar.csv")
    transact(transactor):
      rollBackOnly:
        val repo    = FileInventoryRepoLive()
        val tracker = FileInventoryTracker(using repo)
        val source  = S3FileSource(bucket, "reports/", s3Client)
        val result  = tracker.detectAndQueue(source)
        assert(result.size == 3)
        assert(result.forall(_.status == FileInventoryStatus.QUEUED))
        assert(result.forall(_.sourceSystem == "S3"))
        assert(
          result.map(_.filePath).toSet == Set(
            s"s3://$bucket/reports/jan.csv",
            s"s3://$bucket/reports/feb.csv",
            s"s3://$bucket/reports/mar.csv"
          )
        )

  test("detectAndQueue is idempotent — second run skips already-queued files"):
    put("idempotency/a.csv")
    put("idempotency/b.csv")
    transact(transactor):
      rollBackOnly:
        val repo    = FileInventoryRepoLive()
        val tracker = FileInventoryTracker(using repo)
        val source  = S3FileSource(bucket, "idempotency/", s3Client)
        val first   = tracker.detectAndQueue(source)
        val second  = tracker.detectAndQueue(source)
        assertEquals(first.size, 2)
        assert(second.isEmpty, "second run should not re-queue already tracked files")

  test("detectAndQueue picks up newly added S3 files on subsequent runs"):
    put("incremental/existing.csv")
    transact(transactor):
      rollBackOnly:
        val repo    = FileInventoryRepoLive()
        val tracker = FileInventoryTracker(using repo)
        val source  = S3FileSource(bucket, "incremental/", s3Client)
        val first   = tracker.detectAndQueue(source)
        assertEquals(first.size, 1)
        put("incremental/new.csv")
        val second = tracker.detectAndQueue(source)
        assertEquals(second.size, 1)
        assertEquals(second.head.filePath, s"s3://$bucket/incremental/new.csv")
