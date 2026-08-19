package onparity.domain.service

import onparity.common.model.model.Properties
import onparity.common.model.{CSVParserConfig, ParserConfig}
import onparity.domain.model.NonRetryableError

import scala.util.Try

class ParserError(msg: String) extends NonRetryableError(msg)

case class ParsedLine(props: Properties, maybeAmount: Option[BigDecimal])

trait Parser:
  @throws(classOf[ParserError])
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
      val amount = amountField.map: index =>
        val amountStr = fields(index)
        Try(BigDecimal(amountStr))
          .getOrElse(throw ParserError(s"could not parse amount $amountStr at index $index"))
      Some(
        ParsedLine(
          maybeAmount = amount,
          props       = for ((index, key) <- columns) yield (key, fields(index))
        )
      )
