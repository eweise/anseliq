package anseliq.domain.service.parser

import anseliq.common.model.JSONParserConfig
import com.jayway.jsonpath.{Configuration, JsonPath, Option as JsonPathOption}

import scala.util.Try

/**
 * JSON-lines: one standalone JSON document per file row (not one big JSON array spanning the file),
 * so — unlike BAI2 — every line parses independently with no carried-forward state.
 * config.fieldMappings pairs a JSONPath expression with the property name to store its value under;
 * config.amountPath is the JSONPath to the amount, mirroring CSV's amountField, since JSON (like
 * CSV, unlike BAI2) has no fixed field layout to infer it from automatically.
 *
 * A JSONPath that doesn't match anything on a given row (e.g. an optional field absent on some
 * rows) is treated as absent, not an error — same leniency as CSVParser silently defaulting a
 * missing field, via json-path's SUPPRESS_EXCEPTIONS option. Malformed JSON on a row, or a value at
 * amountPath that isn't a valid number, both throw ParserError, matching how a malformed CSV/BAI2
 * amount is treated as a real data-quality problem rather than something to silently skip.
 */
class JSONParser(config: JSONParserConfig) extends Parser:
  private val jsonPathConfig = Configuration.defaultConfiguration().addOptions(JsonPathOption.SUPPRESS_EXCEPTIONS)

  override def parse(text: String, lineNumber: Long): Option[ParsedLine] =
    if text.isBlank then None
    else
      val document =
        try JsonPath.using(jsonPathConfig).parse(text)
        catch
          case scala.util.control.NonFatal(e) => throw ParserError(s"invalid JSON at line $lineNumber: ${e.getMessage}")

      val props = config.fieldMappings.flatMap((path, name) => readValue(document, path).map(name -> _))

      val amount = config.amountPath.flatMap(readValue(document, _)).map { amountStr =>
        Try(BigDecimal(amountStr))
          .getOrElse(throw ParserError(s"could not parse amount '$amountStr' at line $lineNumber"))
      }

      Some(ParsedLine(props = props, maybeAmount = amount))

  private def readValue(document: com.jayway.jsonpath.DocumentContext, path: String): Option[String] =
    Option(document.read[Any](path)).map(_.toString)
