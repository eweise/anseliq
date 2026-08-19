package onparity.testing

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, ListObjectsV2Request, PutObjectRequest}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

class PopulateS3Buckets extends munit.FunSuite:

  test("1 to 1 attribute test"):
    val bucket             = "onparity"
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

    for i <- 0 until 10 do {
      var bankContents     = ""
      var internalContents = ""
      for (x <- 1 until 100) {
        val transactionID = UUID.randomUUID()
        bankContents += s"""$transactionID,${x * 10}\n"""
        internalContents += s"""$transactionID,${x * 10},${OffsetDateTime.now()}\n"""
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

    val objects = s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
