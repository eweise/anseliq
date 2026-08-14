package onparity.domain.service

import onparity.common.model.{CSVParserConfig, ParserConfig}
import onparity.common.model.model.Properties

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

