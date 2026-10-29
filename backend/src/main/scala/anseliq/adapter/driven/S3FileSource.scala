package anseliq.adapter.driven

import anseliq.common.model.{ConnectionConfig, S3ConnectionConfig}
import anseliq.domain.model.{FileSourceEntry, S3FileSourceEntry}
import anseliq.domain.port.driven.{FileSource, FileSourceFactory}
import com.typesafe.config.Config
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentialsProvider,
  DefaultCredentialsProvider,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsV2Request}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.io.InputStream
import java.net.URI
import java.time.ZoneOffset
import scala.jdk.CollectionConverters.*

class S3FileSourceFactory(region: String, val endpoint: Option[String], credentials: AwsCredentialsProvider)
    extends FileSourceFactory:
  val s3Client: S3Client =
    val builder = S3Client.builder().region(Region.of(region)).credentialsProvider(credentials)
    // An endpoint is only set for an S3 stand-in like LocalStack, which serves every bucket from one
    // host, so it needs path-style URLs; real S3 gets the SDK's own endpoint and URL style.
    endpoint.foreach: url =>
      builder.endpointOverride(URI.create(url))
      builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
    builder.build()

  def createFileSource(conn: ConnectionConfig): S3FileSource =
    S3FileSource(conn.asInstanceOf[S3ConnectionConfig], s3Client)

object S3FileSourceFactory:
  /**
   * A fixed key from s3.username/s3.password if both are set; with neither, the SDK's default
   * credentials chain, which finds the pod's IAM role (IRSA or EKS Pod Identity),
   * AWS_ACCESS_KEY_ID, or ~/.aws.
   */
  def fromConfig(config: Config): S3FileSourceFactory =
    def optional(path: String) = Option.when(config.hasPath(path))(config.getString(path)).filter(_.nonEmpty)
    val credentials = (optional("s3.username"), optional("s3.password")) match
      case (Some(accessKeyId), Some(secret)) =>
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secret))
      case (None, None) => DefaultCredentialsProvider.create()
      case _            => throw IllegalStateException("Set both S3_USERNAME and S3_PASSWORD, or neither")
    S3FileSourceFactory(config.getString("s3.region"), optional("s3.endpoint"), credentials)

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
