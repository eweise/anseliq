package perfectpair.adapter.driven

import perfectpair.common.model.{ConnectionConfig, S3ConnectionConfig}
import perfectpair.domain.model.{FileSourceEntry, S3FileSourceEntry}
import perfectpair.domain.port.driven.{FileSource, FileSourceFactory}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsV2Request}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration, S3Uri}

import java.io.InputStream
import java.net.URI
import java.time.ZoneOffset
import scala.jdk.CollectionConverters.*

class S3FileSourceFactory extends FileSourceFactory:
  val s3Client: S3Client =
    S3Client
      .builder()
      .region(Region.US_EAST_1)
      .endpointOverride(URI.create("http://localhost:4566")) // fixme needs config
      .credentialsProvider(
        StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")) // fixme remove
      )
      .serviceConfiguration(S3Configuration.builder().build())
      .build()

  def createFileSource(conn: ConnectionConfig): S3FileSource =
    S3FileSource(conn.asInstanceOf[S3ConnectionConfig], s3Client)

class S3FileSource(con: S3ConnectionConfig, s3Client: S3Client) extends FileSource:
  val sourceSystem = "S3"

  def listFiles(): Vector[FileSourceEntry] =
    val req = ListObjectsV2Request
      .builder()
      .bucket(con.s3Bucket)
      .prefix(con.filePath)
      .build()

    var bucketName = ""

    s3Client
      .listObjectsV2Paginator(req)
      .stream()
      .flatMap(response =>
        bucketName = response.name()
        response.contents().stream()
      )
      .map(obj =>
        S3FileSourceEntry(
          bucket         = bucketName,
          key            = obj.key(),
          fileSizeBytes  = obj.size(),
          lastModifiedAt = obj.lastModified().atOffset(ZoneOffset.UTC)
        )
      )
      .iterator()
      .asScala
      .toVector

  def getFile(entry: FileSourceEntry): InputStream =
    val s3Entry = entry.asInstanceOf[S3FileSourceEntry]
    s3Client.getObject(
      GetObjectRequest
        .builder()
        .bucket(con.s3Bucket)
        .key(s3Entry.key)
        .build()
    )
