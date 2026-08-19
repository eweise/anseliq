package onparity.adapter.driven

import onparity.common.model.{ConnectionConfig, S3ConnectionConfig}
import onparity.domain.model.{FileSourceEntry, S3FileSourceEntry}
import onparity.domain.port.driven.{FileSource, FileSourceFactory}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsV2Request}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

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
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .build()

  def createFileSource(conn: ConnectionConfig): S3FileSource =
    S3FileSource(conn.asInstanceOf[S3ConnectionConfig], s3Client)

class S3FileSource(con: S3ConnectionConfig, s3Client: S3Client) extends FileSource:
  val sourceSystem = "S3"

  def listFiles(): Vector[FileSourceEntry] =
    val req = ListObjectsV2Request
      .builder()
      .bucket(con.s3Bucket)
      .prefix(s"${con.filePath.stripSuffix("/")}/")
      .build()

    s3Client
      .listObjectsV2(req)
      .contents()
      .stream()
      .map(seObject =>
        S3FileSourceEntry(
          bucket         = con.s3Bucket,
          key            = seObject.key(),
          fileSizeBytes  = seObject.size(),
          lastModifiedAt = seObject.lastModified().atOffset(ZoneOffset.UTC)
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
