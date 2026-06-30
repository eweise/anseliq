package perfectpair.domain.service

import ox.flow.Flow
import perfectpair.common.model.model.ID
import perfectpair.domain.model.*
import perfectpair.domain.model.FileInventoryStatus.COMPLETED
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.port.driven.*

import java.io.InputStream
import java.time.Instant.now

case class IngestionResult(
  recordCount:          Int,
  ingestionTimeSeconds: Long
)

class Ingestor(using
  val movementRepo:      MovementRepo,
  val movementSpecRepo:  MovementSpecRepo,
  val accountRepo:       AccountRepo,
  val eventStore:        EventStore,
  val fileInventoryRepo: FileInventoryRepo,
  val fileSourceFactory: FileSourceFactory
):

  def ingestFileFromInventory(fileInventoryID: ID): Txn[IngestionResult] =
    val fileInventory = fileInventoryRepo
      .findById(fileInventoryID)
      .ErrIfNotFound("FileInventory", fileInventoryID)

    // fixme there is probably a better way to make the relationship between file source
    val entry = fileInventory.fileSourceEntry()

    val movementSpec = movementSpecRepo
      .findById(fileInventory.movementSpecId)
      .ErrIfNotFound("MovementSpec", fileInventory.movementSpecId)

    val connectionConfig = movementSpec.dataSourceConfig().connectionConfig
    val fileSource       = fileSourceFactory.createFileSource(connectionConfig)

    val inputStream = fileSource.getFile(fileInventory.fileSourceEntry())

    ingest(movementSpec, inputStream)

  def ingest(movementSpec: MovementSpec, is: InputStream): Txn[IngestionResult] =
    var lineCounter = 0
    val beginTime   = now()
    val parser      = Parser(movementSpec.dataSourceConfig().parserConfig)
    val inAccount = accountRepo
      .findById(movementSpec.inAccountId)
      .getOrElse(
        throw NotFoundError(s"inAccountID ${movementSpec.inAccountId} not found")
      )
    val outAccount = accountRepo
      .findById(movementSpec.outAccountId)
      .getOrElse(
        throw NotFoundError(s"outAccountID ${movementSpec.outAccountId} not found")
      )
    Flow
      .fromInputStream(is)
      .linesUtf8
      .zipWithIndex
      .map(parser.parse)
      .filter(_.nonEmpty) // get rid of lines that we don't want
      .map(someParsedLine =>
        val parsedLine = someParsedLine.get
        val movement = Movement(
          inAccountId       = movementSpec.inAccountId,
          inAccountBalance  = inAccount.balance,
          outAccountId      = movementSpec.outAccountId,
          outAccountBalance = outAccount.balance,
          amount            = parsedLine.maybeAmount.getOrElse(BigDecimal(0)),
          properties        = JSONBProps(parsedLine.props),
          movementSpecId    = movementSpec.id
        )
        movement
      )
      .grouped(100)
      .runForeach { movements =>
        lineCounter += movements.size
        movementRepo.insertAll(movements)
        accountRepo.update(inAccount)
        accountRepo.update(outAccount)
      }

    eventStore.publishEvent(MoneyMovementsIngested(movementSpecID = movementSpec.id))

    IngestionResult(
      recordCount          = lineCounter,
      ingestionTimeSeconds = now().getEpochSecond - beginTime.getEpochSecond
    )

  def detectAndQueue(movementSpecID: ID, source: FileSource): Txn[Vector[FileInventory]] =
    val existingFileSourceEntries = fileInventoryRepo
      .findBySourceSystem(source.sourceSystem)
      .map(inventory => inventory.fileSourceEntry())
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

  def markComplete(fileInventoryID: ID): Txn[Unit] = fileInventoryRepo.update(
    fileInventoryRepo
      .findById(fileInventoryID)
      .ErrIfNotFound("FileInventory", fileInventoryID)
      .copy(status = COMPLETED)
  )
