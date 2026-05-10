package perfectpair.domain.service

import ox.flow.Flow
import perfectpair.common.model.model.Properties
import perfectpair.common.model.{CSVParserConfig, ParserConfig, ReconciliationInfo}
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{Movement, MovementSpec, NotFoundError}
import perfectpair.domain.port.driven.{AccountRepo, MovementRepo, Txn}

import java.io.InputStream
import java.time.Instant.now

case class ParsedLine(props: Properties, maybeAmount: Option[BigDecimal])

trait Parser:
  def parse(text: String, lineNumber: Long): Option[ParsedLine]

object Parser:
  def apply(config: ParserConfig): Parser =
    config match
      case csv: CSVParserConfig =>
        CSVParser(
          hasHeader   = csv.hasHeader,
          columns     = csv.columnDefinitions,
          amountField = csv.amountField
        )

case class CSVParser(
  columns:     Map[Int, String],
  amountField: Option[Int],
  hasHeader:   Boolean = false
) extends Parser:
  override def parse(text: String, lineNumber: Long): Option[ParsedLine] =
    val fields = text.split(",") // todo replace with real csv parser
    if fields.length < 2 then None
    else if lineNumber == 0 && hasHeader then None
    else
      val amount = amountField.map(index =>
        val amountStr = fields(index)
        BigDecimal(amountStr)
      )
      Some(
        ParsedLine(
          maybeAmount = amount,
          props       = for ((index, key) <- columns) yield (key, fields(index))
        )
      )

case class IngestionResult(
  recordCount:          Int,
  ingestionTimeSeconds: Long
)

class Ingestor(using
  val movementRepo: MovementRepo,
  val accountRepo:  AccountRepo
):

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
          inAccountId    = movementSpec.inAccountId,
          outAccountId   = movementSpec.outAccountId,
          amount         = parsedLine.maybeAmount.getOrElse(BigDecimal(0)),
          properties     = JSONBProps(parsedLine.props),
          movementSpecId = movementSpec.id
        )
        inAccount.balance += movement.amount
        outAccount.balance -= movement.amount
        movement
      )
      .grouped(100)
      .runForeach { movements =>
        lineCounter += movements.size
        movementRepo.insertAll(movements)
        accountRepo.update(inAccount)
        accountRepo.update(outAccount)
      }
    IngestionResult(
      recordCount          = lineCounter,
      ingestionTimeSeconds = now().getEpochSecond - beginTime.getEpochSecond
    )
