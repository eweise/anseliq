package anseliq.domain.service

import com.augustnagro.magnum.{DbTx, Transactor, transact}
import com.fasterxml.uuid.Generators
import com.typesafe.scalalogging.Logger
import net.logstash.logback.argument.StructuredArguments.kv
import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.model.FileInventoryStatus.COMPLETED
import anseliq.domain.port.driven.*
import ox.flow.Flow

import java.io.InputStream
import java.time.Instant.now
import java.time.OffsetDateTime
import scala.collection.mutable

case class IngestionResult(
  recordCount:          Int,
  ingestionTimeSeconds: Long,
  lastProcessedLine:    Option[Long] = None,
  completed:            Boolean      = true
)

case class BatchProgress(linesProcessedInBatch: Int, lastLineInBatch: Long)

case class ParsedRowResult(lineNumber: Long, props: Map[String, String], amount: Option[BigDecimal])
case class ParseErrorResult(lineNumber: Long, message: String)
case class ParseTestResult(
  rows:           Seq[ParsedRowResult],
  errors:         Seq[ParseErrorResult],
  linesProcessed: Int,
  truncated:      Boolean // true if the file had more than maxLines lines and was cut short
)

enum BatchDecision:
  case Commit, Rollback

private case object IngestionStopped extends RuntimeException

class Ingestor(using
  val ledger:             Ledger,
  val movementRepo:       MovementRepo,
  val movementSpecRepo:   MovementSpecRepo,
  val fileFeedRepo:       FileFeedRepo,
  val eventStore:         EventStore,
  val fileInventoryRepo:  FileInventoryRepo,
  val fileSourceFactory:  FileSourceFactory,
  val disputeService:     DisputeService,
  val clock:              ClockService,
  val transactor:         Transactor
):

  def ingestFileFromInventory(
    fileInventoryID: ID,
    batchSize:       Int                            = 1000,
    onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit
  ): IngestionResult =
    val (fileInventory, fileFeed, movementSpecs) = transact(transactor):

      val fileInventory = fileInventoryRepo
        .findById(fileInventoryID)
        .ErrIfNotFound("FileInventory", fileInventoryID)

      val fileFeed = fileFeedRepo
        .findById(fileInventory.fileFeedId)
        .ErrIfNotFound("FileFeed", fileInventory.fileFeedId)

      val movementSpecs = movementSpecRepo.findByFileFeedId(fileFeed.id)

      (fileInventory, fileFeed, movementSpecs)
    end val

    val connectionConfig = fileFeed.dataSourceConfig().connectionConfig
    val fileSource       = fileSourceFactory.createFileSource(connectionConfig)
    val fileSourceEntry  = fileInventory.fileSourceEntry()
    val inputStream      = fileSource.getFile(fileSourceEntry)
    val startingLine     = fileInventory.lastProcessedLine.map(_ + 1).getOrElse(0L)

    val result = ingestResumable(
      fileFeed        = fileFeed,
      movementSpecs   = movementSpecs,
      fileSourceEntry = fileSourceEntry,
      is              = inputStream,
      startingLine    = startingLine,
      batchSize       = batchSize,
      fileInventoryID = Some(fileInventory.id),
      onBatchComplete = onBatchComplete
    )

    if result.completed then
      transact(transactor):
        fileInventoryRepo.update(
          fileInventoryRepo
            .findById(fileInventory.id)
            .ErrIfNotFound("FileInventory", fileInventory.id)
            .copy(status = FileInventoryStatus.COMPLETED)
        )

    result

  def ingestResumable(
    fileFeed:        FileFeed,
    movementSpecs:   Seq[MovementSpec],
    fileSourceEntry: FileSourceEntry,
    is:              InputStream,
    startingLine:    Long                           = 0,
    batchSize:       Int                            = 10000,
    fileInventoryID: Option[ID]                     = None,
    onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit
  ): IngestionResult =
    val logger    = Logger(getClass.getName)
    val beginTime = now()
    val parser    = Parser(fileFeed.dataSourceConfig().parserConfig)

    var recordCount       = 0
    var lastCommittedLine = Option.empty[Long]
    var completed         = true
    val recordCountBySpec = mutable.Map.empty[ID, Int]
    val unroutedRows      = mutable.Buffer.empty[Map[String, String]]
    var parseErrorCount   = 0
    val parseErrorSamples = mutable.Buffer.empty[ParseErrorResult]

    try
      Flow
        .fromInputStream(is)
        .linesUtf8
        .zipWithIndex
        .drop(startingLine.toInt)
        .map { (line, idx) =>
          try (idx, parser.parse(line, idx))
          catch
            case e: ParserError =>
              parseErrorCount += 1
              if parseErrorSamples.size < 10 then parseErrorSamples += ParseErrorResult(idx, e.getMessage)
              // Stop-in-place, not all-or-nothing: batches already committed before this point stay
              // ledgered (see raiseExcessiveParseErrorsDispute's doc). A genuinely systemic parsing
              // problem (wrong delimiter, bad column mapping) surfaces its errors from line 1, so in
              // the common case this behaves like rejecting the whole file anyway.
              if fileFeed.maxParseErrors.exists(cutoff => parseErrorCount > cutoff) then
                completed = false
                throw IngestionStopped
              (idx, None)
        }
        .collect { case (idx, Some(parsedLine)) =>
          val amount = parsedLine.maybeAmount.getOrElse(BigDecimal(0))
          val idempotencyId = Generators
            .nameBasedGenerator()
            .generate(s"${fileSourceEntry.name} $idx")
          val metaData = Map[String, String](
            "file_source_entry" -> fileSourceEntry.name,
            "file_index"        -> idx.toString
          )
          val request = MovementRequest(
            amount      = amount,
            referenceId = idempotencyId,
            properties  = parsedLine.props,
            metaData    = metaData
          )
          (idx, matchSpec(movementSpecs, parsedLine.props), request)
        }
        .grouped(batchSize)
        .runForeach: batch =>
          logger.debug(s"running batch", kv("batch_size", batch.size))
          val lastLineInBatch = batch.last._1
          // Split the batch by which spec (if any) each row's routing key matched, so a batch
          // spanning multiple accounts (a multi-account BAI-style feed) posts each group to its
          // own account pair via a separate ledger.recordMovements call instead of forcing every
          // row in the batch through one spec.
          val byMatchedSpec = batch.groupMap(_._2)(_._3)

          val decision = transact(transactor):
            byMatchedSpec.foreach:
              case (Some(spec), requests) => ledger.recordMovements(movementSpec = spec, movementRequests = requests)
              case (None, _)              => () // unrouted rows aren't recorded as movements

            fileInventoryID.foreach: id =>
              val fileInventory = fileInventoryRepo.findById(id).ErrIfNotFound("FileInventory", id)
              fileInventoryRepo.update(
                fileInventory.copy(
                  lastProcessedLine = Some(lastLineInBatch),
                  status            = FileInventoryStatus.PROCESSING
                )
              )

            val progress      = BatchProgress(linesProcessedInBatch = batch.size, lastLineInBatch = lastLineInBatch)
            val batchDecision = onBatchComplete(progress)
            if batchDecision == BatchDecision.Rollback then summon[DbTx].connection.rollback()
            batchDecision

          decision match
            case BatchDecision.Commit =>
              byMatchedSpec.foreach:
                case (Some(spec), requests) =>
                  recordCount += requests.size
                  recordCountBySpec.updateWith(spec.id)(c => Some(c.getOrElse(0) + requests.size))
                case (None, requests) =>
                  unroutedRows ++= requests.map(_.properties)
              lastCommittedLine = Some(lastLineInBatch)
              logger.info("committing batch", kv("record_count", recordCount))
            case BatchDecision.Rollback =>
              completed = false
              throw IngestionStopped
    catch
      case IngestionStopped               => ()
      case scala.util.control.NonFatal(e) => throw ReconError(s"ingest failed. filename=${fileSourceEntry.name}", e)

    if lastCommittedLine.isDefined then
      transact(transactor):
        recordCountBySpec.foreach: (specId, count) =>
          val spec = movementSpecs.find(_.id == specId).get
          eventStore.publishEvent(
            MoneyMovementsIngested(
              movementSpecID       = spec.id,
              movementSpecName     = spec.name,
              fileName             = fileSourceEntry.name,
              ingestionTimeSeconds = now().getEpochSecond - beginTime.getEpochSecond,
              lastProcessedLine    = lastCommittedLine,
              recordCount          = count
            )
          )
        if unroutedRows.nonEmpty then raiseUnroutedRecordDispute(fileFeed, movementSpecs, unroutedRows.toSeq)

    // Not gated on lastCommittedLine.isDefined like the block above: the cutoff can be crossed
    // before a single batch ever commits (a bad file fails from line 1), and the dispute still
    // needs to be raised in that case.
    if fileFeed.maxParseErrors.exists(cutoff => parseErrorCount > cutoff) then
      transact(transactor):
        raiseExcessiveParseErrorsDispute(fileFeed, parseErrorCount, parseErrorSamples.toSeq)

    IngestionResult(
      recordCount          = recordCount,
      ingestionTimeSeconds = now().getEpochSecond - beginTime.getEpochSecond,
      lastProcessedLine    = lastCommittedLine,
      completed            = completed
    )

  // Finds which of a feed's MovementSpecs claims a parsed row's properties: any spec with a
  // RoutingFilter whose propertyField/matchValue matches wins over a catch-all spec (routingFilter
  // = None, claims everything), so a feed can mix a few explicitly-routed accounts with a
  // fallback if one is configured. None means the row belongs to no configured spec at all.
  private def matchSpec(movementSpecs: Seq[MovementSpec], props: Map[String, String]): Option[MovementSpec] =
    movementSpecs
      .find(_.routingFilter().exists(f => props.get(f.propertyField).contains(f.matchValue)))
      .orElse(movementSpecs.find(_.routingFilter().isEmpty))

  // Raised once per ingest run (not once per row) when one or more parsed rows matched no
  // configured MovementSpec's routing filter — the actual fix for the silent-money-loss failure
  // mode a multi-account feed (e.g. BAI2) has if the bank starts including an account nobody's
  // configured a spec for: those rows now surface as an exception instead of vanishing. Unlike
  // FileMissing there's no clean signal to auto-resolve on, so this stays open until a human
  // configures a matching spec and resolves it explicitly.
  private def raiseUnroutedRecordDispute(
    fileFeed:      FileFeed,
    movementSpecs: Seq[MovementSpec],
    unroutedRows:  Seq[Map[String, String]]
  ): Txn[Unit] =
    val routedFields = movementSpecs.flatMap(_.routingFilter().map(_.propertyField)).distinct
    val sample = routedFields
      .map(field => field -> unroutedRows.flatMap(_.get(field)).distinct.take(10))
      .filter(_._2.nonEmpty)
    val valuesSummary = sample.map((field, values) => s"$field=${values.mkString(",")}").mkString("; ")
    val detail =
      s"${unroutedRows.size} row(s) in '${fileFeed.name}' matched no configured MovementSpec." +
        (if valuesSummary.nonEmpty then s" Unmatched value(s): $valuesSummary" else "")

    disputeService.raiseSystemDispute(fileFeed.id, DisputeTarget.FileFeed, DisputeCategory.UnroutedRecord, detail)

  // Raised once ingestion stops because a file's parse-error count crossed fileFeed.maxParseErrors
  // (see the .map step in ingestResumable above) — a signal the file has a general parsing problem
  // (wrong delimiter, bad column mapping, wrong feed pointed at wrong parser config), not just a
  // few bad rows. Stop-in-place, not all-or-nothing: rows from batches already committed before the
  // cutoff was crossed stay ledgered — only rows from the point the cutoff was crossed onward are
  // never parsed/ledgered. Targets the FileFeed for the same reason FileMissing/UnroutedRecord do:
  // there's no clean single row to blame for "this file is too dirty."
  private def raiseExcessiveParseErrorsDispute(
    fileFeed:   FileFeed,
    errorCount: Int,
    samples:    Seq[ParseErrorResult]
  ): Txn[Unit] =
    val sampleSummary = samples.map(s => s"line ${s.lineNumber}: ${s.message}").mkString("; ")
    val detail =
      s"Ingestion of '${fileFeed.name}' stopped after $errorCount parse error(s) exceeded the " +
        s"configured cutoff (${fileFeed.maxParseErrors.get})." +
        (if sampleSummary.nonEmpty then s" Sample error(s): $sampleSummary" else "")

    disputeService.raiseSystemDispute(fileFeed.id, DisputeTarget.FileFeed, DisputeCategory.ExcessiveParseErrors, detail)

  /**
   * Lets a user try a feed's parser config against a real sample file before wiring up real
   * ingestion, entirely in memory — no FileInventory row, no Movements written, nothing persisted
   * at all. Unlike ingestResumable, a row that throws ParserError doesn't abort the whole run; it's
   * collected alongside the successful rows so the user can see exactly which lines fail and why,
   * in one pass. maxLines caps how many lines of the upload are ever parsed, regardless of the
   * file's real size, so a user can't accidentally hang the request on a huge upload.
   *
   * Takes an already-resolved FileFeed (the caller does the lookup) — this method itself touches no
   * DB at all, purely in-memory parsing, which also makes it trivial to unit test directly.
   */
  def testParse(fileFeed: FileFeed, content: String, maxLines: Int = 1000): ParseTestResult =
    val parser = Parser(fileFeed.dataSourceConfig().parserConfig)

    val allLines  = content.linesIterator.take(maxLines + 1).toVector
    val truncated = allLines.size > maxLines
    val lines     = if truncated then allLines.dropRight(1) else allLines

    val rows   = scala.collection.mutable.ArrayBuffer.empty[ParsedRowResult]
    val errors = scala.collection.mutable.ArrayBuffer.empty[ParseErrorResult]

    lines.zipWithIndex.foreach { (line, idx) =>
      try
        parser.parse(line, idx).foreach { parsed =>
          rows += ParsedRowResult(idx, parsed.props, parsed.maybeAmount)
        }
      catch case e: ParserError => errors += ParseErrorResult(idx, e.getMessage)
    }

    ParseTestResult(rows.toSeq, errors.toSeq, lines.size, truncated)

  def detectAndQueue(fileFeedID: ID, source: FileSource): Txn[Vector[FileInventory]] =
    val inventories = fileInventoryRepo
      .findBySourceSystem(source.sourceSystem)

    val existingFileSourceEntries =
      inventories.map: inventory =>
        inventory.fileSourceEntry()
    val sourceFiles = source.listFiles()
    val newFiles    = sourceFiles.filterNot(f => existingFileSourceEntries.contains(f))

    newFiles.map: newFile =>
      val fileInventory: FileInventory = createFileInventory(fileFeedID, source, newFile)
      fileInventoryRepo.insert(fileInventory)
      eventStore.publishEvent(
        NewFileDetected(
          fileInventoryID = fileInventory.id,
          filePath        = newFile.name,
          source          = newFile.getClass.getSimpleName
        )
      )
      fileInventory

  // separated out method so it could be overridden in tests
  protected def createFileInventory(fileFeedID: ID, source: FileSource, newFile: FileSourceEntry): FileInventory = {
    val fileSourceEntryJson = upickle.write(newFile)
    val now                 = clock.now
    val fileInventory = FileInventory(
      fileFeedId          = fileFeedID,
      sourceSystem        = source.sourceSystem,
      fileSourceEntryJson = fileSourceEntryJson,
      fileSizeBytes       = newFile.fileSizeBytes,
      lastModifiedAt      = newFile.lastModifiedAt,
      status              = FileInventoryStatus.QUEUED,
      lastProcessedAt     = Some(now),
      updatedAt           = Some(now)
    )
    fileInventory
  }

  /**
   * Auto-raises (or auto-resolves) a FileMissing dispute against the FileFeed itself, based on
   * whether a new file has been detected within its expectedCadenceHours window. Unlike
   * AmountMismatch (which always targets a real Reconciliation row), a missing file has no
   * FileInventory row to reference — there's nothing to point at except the feed that expected it.
   * A no-op when expectedCadenceHours isn't configured (not every feed is fed by a scheduled file
   * drop, so there's nothing to compare against). The baseline for "since when" is the most
   * recently seen file for this feed, or the feed's own createdAt if none has ever arrived.
   */
  def checkForMissingFile(fileFeed: FileFeed): Txn[Unit] =
    fileFeed.expectedCadenceHours.foreach: cadenceHours =>
      val lastSeenAt =
        fileInventoryRepo
          .findLatestByFileFeedId(fileFeed.id)
          .map(_.lastModifiedAt)
          .orElse(fileFeed.createdAt)
      lastSeenAt.foreach: since =>
        if clock.now.isAfter(since.plusHours(cadenceHours)) then raiseFileMissingDispute(fileFeed, cadenceHours, since)
        else resolveFileMissingDispute(fileFeed)

  // raiseSystemDispute is idempotent, so repeated scans don't stack disputes: a rerun of this feed,
  // or a feed that's still overdue on a later cycle, leaves the one open dispute as it is.
  private def raiseFileMissingDispute(fileFeed: FileFeed, cadenceHours: Int, since: OffsetDateTime): Txn[Unit] =
    disputeService.raiseSystemDispute(
      fileFeed.id,
      DisputeTarget.FileFeed,
      DisputeCategory.FileMissing,
      s"No new file detected for '${fileFeed.name}' since $since (expected every $cadenceHours hours)"
    )

  // The mirror image of raiseFileMissingDispute: once a file shows up within cadence again, any
  // open FileMissing dispute for this feed is stale and resolves itself.
  private def resolveFileMissingDispute(fileFeed: FileFeed): Txn[Unit] =
    disputeService.resolveAutomatically(
      fileFeed.id,
      DisputeCategory.FileMissing,
      s"Auto-resolved: a new file was detected for '${fileFeed.name}'."
    )

  def markComplete(fileInventoryID: ID): Txn[Unit] = fileInventoryRepo.update(
    fileInventoryRepo
      .findById(fileInventoryID)
      .ErrIfNotFound("FileInventory", fileInventoryID)
      .copy(status = COMPLETED)
  )
