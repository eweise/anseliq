package anseliq.domain.service.parser

import anseliq.common.model.{FixedWidthField, FixedWidthParserConfig}

import scala.util.Try

/**
 * Positional source: every line has the same field layout at fixed character positions, as commonly
 * described in mainframe/bank file specs. Positions are 1-based and inclusive (e.g. start=1, end=10
 * is the first 10 characters) — the opposite convention from CSV's 0-indexed column numbers —
 * because that's how such specs are conventionally written for non-programmers transcribing them
 * from a document, not because of anything in this codebase.
 *
 * A line shorter than a field's declared range yields a truncated (possibly empty) value rather
 * than throwing, since real-world fixed-width files commonly have trailing whitespace trimmed by
 * whatever produced them. Every extracted value is trimmed, since fixed-width fields are
 * conventionally space-padded to their declared width.
 */
class FixedWidthParser(config: FixedWidthParserConfig) extends Parser:
  override def parse(text: String, lineNumber: Long): Option[ParsedLine] =
    if text.isBlank then None
    else if lineNumber == 0 && config.hasHeader then None
    else
      def extract(field: FixedWidthField): String =
        val len   = text.length
        val start = math.max(0, math.min(field.startPosition - 1, len))
        val end   = math.max(start, math.min(field.endPosition, len))
        text.substring(start, end).trim

      val props = config.fields.map(f => f.name -> extract(f)).toMap
      val amount = config.fields.find(_.isAmount).map { f =>
        val amountStr = extract(f)
        Try(BigDecimal(amountStr))
          .getOrElse(throw ParserError(s"could not parse amount '$amountStr' at line $lineNumber"))
      }
      Some(ParsedLine(props = props, maybeAmount = amount))
