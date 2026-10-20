package anseliq.testing

import anseliq.common.model.{ConnectionConfig, S3ConnectionConfig}
import anseliq.domain.model.{FileSourceEntry, InMemoryFileSourceEntry}
import anseliq.domain.port.driven.{FileSource, FileSourceFactory}
import anseliq.domain.service.ClockService

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.concurrent.TrieMap

// A FileSourceFactory over files held in memory, keyed by each feed's bucket and path, so a scenario
// can drop a file onto a feed and have the real scan and ingest code pick it up without LocalStack.
// A dropped file's lastModifiedAt is the clock's now, as if it had just landed.
class InMemoryFileSources(clock: ClockService) extends FileSourceFactory:
  private val files = TrieMap.empty[String, Vector[(InMemoryFileSourceEntry, String)]]

  private def location(conn: ConnectionConfig): String = conn match
    case s3: S3ConnectionConfig => s"${s3.s3Bucket}/${s3.filePath.stripSuffix("/")}"

  def drop(conn: ConnectionConfig, fileName: String, content: String): Unit =
    val entry = InMemoryFileSourceEntry(
      name           = s"${location(conn)}/$fileName",
      fileSizeBytes  = content.getBytes(UTF_8).length.toLong,
      lastModifiedAt = clock.now
    )
    files.updateWith(location(conn))(existing => Some(existing.getOrElse(Vector.empty) :+ (entry -> content)))

  def createFileSource(conn: ConnectionConfig): FileSource = new FileSource:
    val sourceSystem = "InMemory"

    def listFiles(): Vector[FileSourceEntry] = files.getOrElse(location(conn), Vector.empty).map(_._1)

    def getFile(entry: FileSourceEntry): InputStream =
      val content = files
        .getOrElse(location(conn), Vector.empty)
        .collectFirst { case (e, content) if e.name == entry.name => content }
        .getOrElse(throw IllegalArgumentException(s"no in-memory file ${entry.name}"))
      ByteArrayInputStream(content.getBytes(UTF_8))
