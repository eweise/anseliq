package perfectpair.domain.service

import ox.flow.Flow
import perfectpair.common.model.model.Properties
import perfectpair.common.model.{CSVParserConfig, ParserConfig}
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{Movement, MovementSpec}
import perfectpair.domain.port.driven.{MovementRepo, Txn}

import java.io.InputStream

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

case class CSVParser(columns: Map[Int, String], amountField: Option[Int], hasHeader: Boolean = false) extends Parser:
  override def parse(text: String, lineNumber: Long): Option[ParsedLine] =
    val fields = text.split(",") // todo replace with real csv parser
    if fields.length < 2 then None
    else if lineNumber == 0 && hasHeader then None
    else
      Some(
        ParsedLine(
          maybeAmount = None,
          props       = for ((index, key) <- columns) yield (key, fields(index))
        )
      )

class Ingestor(
  val movementRepo: MovementRepo
):

  def ingest(movementSpec: MovementSpec, is: InputStream): Txn[Unit] =
    var lineCounter = 0
    val parser      = Parser(movementSpec.dataSourceConfig().parserConfig)

    Flow
      .fromInputStream(is)
      .linesUtf8
      .zipWithIndex
      .map(parser.parse)
      .filter(_.nonEmpty) // get rid of lines that we don't want
      .map(someParsedLine =>
        val parsedLine = someParsedLine.get
        Movement(
          inAccountId    = movementSpec.inAccountId,
          outAccountId   = movementSpec.outAccountId,
          amount         = parsedLine.maybeAmount.getOrElse(BigDecimal(0)),
          properties     = JSONBProps(parsedLine.props),
          movementSpecId = movementSpec.id
        )
      )
      .grouped(100)
      .runForeach(movementRepo.insertAll)
