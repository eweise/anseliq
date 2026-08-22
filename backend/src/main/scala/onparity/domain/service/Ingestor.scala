package onparity.domain.service

import com.augustnagro.magnum.{DbTx, Transactor, transact}
import com.fasterxml.uuid.Generators
import com.typesafe.scalalogging.Logger
import net.logstash.logback.argument.StructuredArguments.kv
import onparity.common.model.model.ID
import onparity.domain.model.*
import onparity.domain.model.FileInventoryStatus.COMPLETED
import onparity.domain.port.driven.*
import ox.flow.Flow

import java.io.InputStream
import java.time.Instant.now

case class IngestionResult(
  recordCount:          Int,
  ingestionTimeSeconds: Long,
  lastProcessedLine:    Option[Long] = None,
  completed:            Boolean      = true
)

case class BatchProgress(linesProcessedInBatch: Int, lastLineInBatch: Long)

enum BatchDecision:
  case Commit, Rollback

private case object IngestionStopped extends RuntimeException

class Ingestor(using
  val ledger:            Ledger,
  val movementRepo:      MovementRepo,
  val movementSpecRepo:  MovementSpecRepo,
  val eventStore:        EventStore,
  val fileInventoryRepo: FileInventoryRepo,
  val fileSourceFactory: FileSourceFactory,
  val disputeRepo:       DisputeRepo,
  val clock:             ClockService,
  val transactor:        Transactor
):

  def ingestFileFromInventory(
    fileInventoryID: ID,
    batchSize:       Int                            = 1000,
    onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit
  ): IngestionResult =
    val (fileInventory, movementSpec) = transact(transactor):

      val fileInventory = fileInventoryRepo
        .findById(fileInventoryID)
        .ErrIfNotFound("FileInventory", fileInventoryID)

      val movementSpec = movementSpecRepo
        .findById(fileInventory.movementSpecId)
        .ErrIfNotFound("MovementSpec", fileInventory.movementSpecId)

      (fileInventory, movementSpec)
    end val

    val connectionConfig = movementSpec.dataSourceConfig().connectionConfig
    val fileSource       = fileSourceFactory.createFileSource(connectionConfig)
    val fileSourceEntry  = fileInventory.fileSourceEntry()
    val inputStream      = fileSource.getFile(fileSourceEntry)
    val startingLine     = fileInventory.lastProcessedLine.map(_ + 1).getOrElse(0L)

    val result = ingestResumable(
      movementSpec    = movementSpec,
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
    movementSpec:    MovementSpec,
    fileSourceEntry: FileSourceEntry,
    is:              InputStream,
    startingLine:    Long                           = 0,
    batchSize:       Int                            = 10000,
    fileInventoryID: Option[ID]                     = None,
    onBatchComplete: BatchProgress => BatchDecision = _ => BatchDecision.Commit
  ): IngestionResult =
    val logger    = Logger(getClass.getName)
    val beginTime = now()
    val parser    = Parser(movementSpec.dataSourceConfig().parserConfig)

    var recordCount       = 0
    var lastCommittedLine = Option.empty[Long]
    var completed         = true

    try
      Flow
        .fromInputStream(is)
        .linesUtf8
        .zipWithIndex
        .drop(startingLine.toInt)
        .map((line, idx) => (idx, parser.parse(line, idx)))
        .collect { case (idx, Some(parsedLine)) =>
          val amount = parsedLine.maybeAmount.getOrElse(BigDecimal(0))
          val idempotencyId = Generators
            .nameBasedGenerator()
            .generate(s"${fileSourceEntry.name} $idx")
          val metaData = Map[String, String](
            "file_source_entry" -> fileSourceEntry.name,
            "file_index"        -> idx.toString
          )
          (
            idx,
            MovementRequest(
              amount      = amount,
              referenceId = idempotencyId,
              properties  = parsedLine.props,
              metaData    = metaData
            )
          )
        }
        .grouped(batchSize)
        .runForeach: batch =>
          logger.debug(s"running batch", kv("batch_size", batch.size))
          val lastLineInBatch = batch.last._1
          val requests        = batch.map(_._2)

          val decision = transact(transactor):
            ledger.recordMovements(movementSpec = movementSpec, movementRequests = requests)

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
              recordCount += batch.size
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
        val s = fileSourceEntry.name
        val m = movementSpec.name
        eventStore.publishEvent(
          MoneyMovementsIngested(
            movementSpecID       = movementSpec.id,
            movementSpecName     = movementSpec.name,
            fileName             = fileSourceEntry.name,
            ingestionTimeSeconds = now().getEpochSecond - beginTime.getEpochSecond,
            lastProcessedLine    = lastCommittedLine,
            recordCount          = recordCount
          )
        )

    IngestionResult(
      recordCount          = recordCount,
      ingestionTimeSeconds = now().getEpochSecond - beginTime.getEpochSecond,
      lastProcessedLine    = lastCommittedLine,
      completed            = completed
    )

  def detectAndQueue(movementSpecID: ID, source: FileSource): Txn[Vector[FileInventory]] =
    val inventories = fileInventoryRepo
      .findBySourceSystem(source.sourceSystem)

    val existingFileSourceEntries =
      inventories.map: inventory =>
        inventory.fileSourceEntry()
    val sourceFiles = source.listFiles()
    val newFiles    = sourceFiles.filterNot(f => existingFileSourceEntries.contains(f))

    newFiles.map: newFile =>
      val fileSourceEntryJson = upickle.write(newFile)
      val fileInventory = FileInventory(
        movementSpecId      = movementSpecID,
        sourceSystem        = source.sourceSystem,
        fileSourceEntryJson = fileSourceEntryJson,
        fileSizeBytes       = newFile.fileSizeBytes,
        lastModifiedAt      = newFile.lastModifiedAt,
        status              = FileInventoryStatus.QUEUED,
        lastProcessedAt     = None,
        updatedAt           = None
      )
      fileInventoryRepo.insert(fileInventory)
      eventStore.publishEvent(NewFileDetected(fileInventoryID = fileInventory.id))
      fileInventory

  /**
   * Auto-raises a FileMissing dispute against the MovementSpec itself when no new file has been
   * detected within its expectedCadenceHours window. Unlike AmountMismatch (which always targets a
   * real Reconciliation row), a missing file has no FileInventory row to reference — there's nothing
   * to point at except the spec that expected it. A no-op when expectedCadenceHours isn't configured
   * (not every spec is fed by a scheduled file drop, so there's nothing to compare against). The
   * baseline for "since when" is the most recently seen file for this spec, or the spec's own
   * createdAt if none has ever arrived; insertIfNoOpenDispute keeps repeated scans idempotent.
   */
  def checkForMissingFile(movementSpec: MovementSpec): Txn[Unit] =
    movementSpec.expectedCadenceHours.foreach: cadenceHours =>
      val lastSeenAt =
        fileInventoryRepo.findLatestByMovementSpecId(movementSpec.id).map(_.lastModifiedAt).orElse(movementSpec.createdAt)
      lastSeenAt.foreach: since =>
        if clock.now.isAfter(since.plusHours(cadenceHours)) then
          val dispute = Dispute(
            referenceId = movementSpec.id,
            targetType  = DisputeTarget.MovementSpec,
            category    = DisputeCategory.FileMissing,
            detail      = Some(s"No new file detected for '${movementSpec.name}' since $since (expected every $cadenceHours hours)"),
            createdAt   = Some(clock.now),
            updatedAt   = Some(clock.now)
          )
          disputeRepo.insertIfNoOpenDispute(dispute).foreach: raised =>
            eventStore.publishEvent(DisputeRaised(disputeID = raised.id))

  def markComplete(fileInventoryID: ID): Txn[Unit] = fileInventoryRepo.update(
    fileInventoryRepo
      .findById(fileInventoryID)
      .ErrIfNotFound("FileInventory", fileInventoryID)
      .copy(status = COMPLETED)
  )
