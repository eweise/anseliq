package perfectpair.testing

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, ListObjectsV2Request, PutObjectRequest}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.net.URI
import java.time.OffsetDateTime

class PopulateS3Buckets extends munit.FunSuite:

  test("1 to 1 attribute test"):
    val bucket             = "perfectpair"
    val localstackEndpoint = URI.create("http://localhost:4566")
    lazy val s3Client: S3Client = S3Client
      .builder()
      .region(Region.US_EAST_1)
      .endpointOverride(localstackEndpoint)
      .credentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
      )
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .build()

    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())

    val buckets = s3Client.listBuckets()
    println(buckets)
    var contents1 = ""
    for (i <- 0 until 100)
      contents1 += s"""$i,${i + 10}\n"""

    var file2 = ""
    for (i <- 0 until 100)
      file2 += s"""$i,${OffsetDateTime.now()},${i + 10}\n"""

    for i <- 0 until 10 do
      s3Client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(s"bank/file$i.csv").build(),
        RequestBody.fromString(contents1)
      )

      s3Client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(s"internal/data$i.csv").build(),
        RequestBody.fromString(contents1)
      )

    val objects = s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
    println(objects.contents())
