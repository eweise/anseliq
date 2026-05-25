package perfectpair.adapter.driven

import perfectpair.domain.port.driven.{FileSource, FileSourceEntry}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request

import java.time.ZoneOffset
import scala.jdk.CollectionConverters.*

class S3FileSource(bucket: String, prefix: String = "", s3Client: S3Client) extends FileSource:
  val sourceSystem = "S3"

  def listFiles(): Vector[FileSourceEntry] =
    val req = ListObjectsV2Request.builder()
      .bucket(bucket)
      .prefix(prefix)
      .build()
    s3Client
      .listObjectsV2Paginator(req)
      .stream()
      .flatMap(_.contents().stream())
      .map(obj =>
        FileSourceEntry(
          filePath       = s"s3://$bucket/${obj.key()}",
          fileSizeBytes  = obj.size(),
          lastModifiedAt = obj.lastModified().atOffset(ZoneOffset.UTC)
        )
      )
      .iterator()
      .asScala
      .toVector
