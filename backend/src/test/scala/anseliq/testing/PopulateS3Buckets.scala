package anseliq.testing

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

class PopulateS3Buckets extends munit.FunSuite:
  val bucket             = "scenario1"
  val localstackEndpoint = URI.create("http://localhost:4566")

  test("1 to 1 attribute test") {
    val (bucket: String, s3Client: S3Client) = init

    for _ <- 0 until 1 do {
      var bankContents     = ""
      var internalContents = ""
      for (x <- 1 until 5) {
        val transactionId = UUID.randomUUID()
        bankContents += s"""$transactionId,${x * 10}\n"""
        internalContents += s"""$transactionId,${x * 10 + (if x == 3 then 1 else 0)},${OffsetDateTime.now()}\n"""
      }
      s3Client.putObject(
        PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(s"bank/file_${UUID.randomUUID()}.csv")
          .build(),
        RequestBody.fromString(bankContents)
      )
      s3Client.putObject(
        PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(s"internal/data_${UUID.randomUUID()}.csv")
          .build(),
        RequestBody.fromString(internalContents)
      )
    }
  }

  test("mismatched ids") {
    val (bucket: String, s3Client: S3Client) = init

    var bankContents     = ""
    var internalContents = ""
    for (x <- 1 until 2) {
      val transactionId = UUID.randomUUID()
      bankContents += s"""$transactionId,${x * 10}\n"""
      internalContents += s"""${transactionId}_B,${x * 10},${OffsetDateTime.now()}\n"""
    }
    s3Client.putObject(
      PutObjectRequest
        .builder()
        .bucket(bucket)
        .key(s"bank/file_${UUID.randomUUID()}.csv")
        .build(),
      RequestBody.fromString(bankContents)
    )
    s3Client.putObject(
      PutObjectRequest
        .builder()
        .bucket(bucket)
        .key(s"internal/data_${UUID.randomUUID()}.csv")
        .build(),
      RequestBody.fromString(internalContents)
    )
  }

  private def init = {
    val bucket             = "scenario1"
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
    (bucket, s3Client)
  }

  test("one to many attribute test"):
    lazy val s3Client: S3Client = createS3Client()

    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())

    for _ <- 0 until 2 do {
      var bankContents     = ""
      var internalContents = ""
      for (x <- 1 until 2) {
        val transactionId = UUID.randomUUID()
        val amount        = x * 10
        bankContents += s"""$transactionId,${amount + 1}\n"""

        val amount1 = amount / 2
        val amount2 = amount - amount1
        internalContents += s"""$transactionId,$amount1,${OffsetDateTime.now()}\n"""
        internalContents += s"""$transactionId,$amount2,${OffsetDateTime.now()}\n"""
      }
      s3Client.putObject(
        PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(s"bank/file_${UUID.randomUUID()}.csv")
          .build(),
        RequestBody.fromString(bankContents)
      )
      s3Client.putObject(
        PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(s"internal/data_${UUID.randomUUID()}.csv")
          .build(),
        RequestBody.fromString(internalContents)
      )
    }

  private def createS3Client() = S3Client
    .builder()
    .region(Region.US_EAST_1)
    .endpointOverride(localstackEndpoint)
    .credentialsProvider(
      StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
    )
    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
    .build()
