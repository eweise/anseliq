package anseliq.domain.service.parser

import anseliq.common.model.*
import anseliq.common.model.model.Properties
import anseliq.domain.model.NonRetryableError
import com.jayway.jsonpath.{Configuration, JsonPath, Option as JsonPathOption}

import scala.util.Try

class ParserError(msg: String) extends NonRetryableError(msg)

case class ParsedLine(props: Properties, maybeAmount: Option[BigDecimal])

trait Parser:
  @throws(classOf[ParserError])
  def parse(text: String, lineNumber: Long): Option[ParsedLine]

object Parser:
  def apply(config: ParserConfig): Parser =
    config match
      case csv: CsvParserConfig =>
        CsvParser(
          hasHeader   = csv.hasHeader,
          columns     = csv.columnDefinitions,
          amountField = csv.amountField
        )
      case bai:  BaiParserConfig        => BaiParser(bai)
      case json: JsonParserConfig       => JsonParser(json)
      case fw:   FixedWidthParserConfig => FixedWidthParser(fw)
