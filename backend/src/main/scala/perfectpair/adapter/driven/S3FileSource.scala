package perfectpair.adapter.driven

import perfectpair.domain.model.{FileSourceEntry, S3FileSourceEntry}
import perfectpair.domain.port.driven.FileSource
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsV2Request}
import software.amazon.awssdk.services.s3.{S3Client, S3Uri}

import java.io.InputStream
import java.time.ZoneOffset
import scala.jdk.CollectionConverters.*

class S3FileSource(bucket: String, prefix: String = "", s3Client: S3Client) extends FileSource:
  val sourceSystem = "S3"

  def listFiles(): Vector[FileSourceEntry] =
    val req = ListObjectsV2Request
      .builder()
      .bucket(bucket)
      .prefix(prefix)
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

  def getFile(filePath: String): InputStream =
    val s3Uri = S3Uri.builder().uri(java.net.URI(filePath))
    s3Client.getObject(
      GetObjectRequest
        .builder()
        .bucket(bucket)
        .key(filePath)
        .build()
    )
