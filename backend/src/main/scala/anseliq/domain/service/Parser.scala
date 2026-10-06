package anseliq.domain.service

import anseliq.common.model.model.Properties
import anseliq.common.model.*
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
      case csv: CSVParserConfig =>
        CSVParser(
          hasHeader   = csv.hasHeader,
          columns     = csv.columnDefinitions,
          amountField = csv.amountField
        )
      case bai:  BAIParserConfig        => BAIParser(bai)
      case json: JSONParserConfig       => JSONParser(json)
      case fw:   FixedWidthParserConfig => FixedWidthParser(fw)

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

/**
 * BAI2 (Bank Administration Institute) cash management format: a hierarchical, comma-delimited
 * format (file > group > account > transaction) — not flat and line-independent like CSV. The
 * record type is the first field on every line: 01 file header, 02 group header, 03 account
 * identifier, 16 transaction detail, 88 continuation (of whichever record preceded it), 49 account
 * trailer, 98 group trailer, 99 file trailer.
 *
 * Only 16 (transaction detail) records become movements. A 16 record isn't emitted the moment it's
 * parsed, though: an 88 line immediately after it extends its free-text field, so it's held in
 * `pending` and only flushed (returned) once the next 16/03/49/98/99 line proves no more
 * continuations are coming — meaning every `parse` call actually emits the *previous* transaction,
 * not the current line's own one. Account number is carried forward from the most recent 03 record
 * into every transaction's props, since a 16 record doesn't repeat its own account number.
 *
 * Known simplifications: BAI2 amounts are unsigned (direction is conveyed by type_code, not sign) —
 * type_code is exposed as a raw property rather than mapped through the full BAI2 type-code table,
 * and funds-type distributed-availability sub-fields aren't structurally parsed, just kept as the
 * raw funds_type string. Resuming ingestion mid-file (startingLine > 0) is NOT safe for BAI2: the
 * account-number context a 03 record establishes would be lost, so BAI2 files should always be
 * ingested from the start in one pass. Relies on the file having well-formed 49/98/99 trailers to
 * flush the final pending transaction — a truncated file missing all trailers would silently drop
 * its last transaction.
 *
 * Which fields actually become properties (and under what name) is driven by
 * `config.fieldMappings`, the same "which + what name" idea as CSV's columnDefinitions — just keyed
 * by the fixed set of known BAI2 fields instead of an arbitrary column index, since BAI2's layout
 * is a standard rather than something each source defines. Amount is not one of those selectable
 * fields: its position is always known, so it's captured automatically into ParsedLine.maybeAmount
 * rather than requiring the user to identify it.
 */
class BAIParser(config: BAIParserConfig = BAIParserConfig()) extends Parser:
  private var currentAccountNumber: Option[String]     = None
  private var pending:              Option[ParsedLine] = None

  override def parse(text: String, lineNumber: Long): Option[ParsedLine] =
    val line = text.stripSuffix("/").trim
    if line.isEmpty then flushPending()
    else
      val fields = line.split(",", -1).map(_.trim)
      fields(0) match
        case "88" =>
          pending = pending.map(appendContinuation(_, fields))
          None
        case "16" =>
          val flushed = flushPending()
          pending = Some(toTransaction(fields))
          flushed
        case "03" =>
          val flushed = flushPending()
          currentAccountNumber = fields.lift(1).filter(_.nonEmpty)
          flushed
        case "49" | "98" | "99" => flushPending()
        // 01/02 headers, or anything unrecognized: no state change, nothing to flush yet.
        case _ => None

  private def flushPending(): Option[ParsedLine] =
    val result = pending
    pending = None
    result

  private def rawFieldValue(field: BAIField, fields: Array[String]): String = field match
    case BAIField.AccountNumber           => currentAccountNumber.getOrElse("")
    case BAIField.TypeCode                => fields.lift(1).getOrElse("")
    case BAIField.FundsType               => fields.lift(3).getOrElse("")
    case BAIField.BankReferenceNumber     => fields.lift(4).getOrElse("")
    case BAIField.CustomerReferenceNumber => fields.lift(5).getOrElse("")
    case BAIField.Text                    => fields.drop(6).mkString(",")

  private def toTransaction(fields: Array[String]): ParsedLine =
    val amountStr = fields.lift(2).getOrElse("")

    // BAI2 amounts are unsigned integers in the minor currency unit (e.g. "150000" = 1500.00),
    // per spec, not a decimal string.
    val amount =
      if amountStr.isEmpty then None
      else
        Some(Try(BigDecimal(amountStr) / 100).getOrElse(throw ParserError(s"could not parse BAI2 amount '$amountStr'")))

    ParsedLine(
      maybeAmount = amount,
      props       = config.fieldMappings.map((field, name) => name -> rawFieldValue(field, fields))
    )

  // If Text isn't a field the user chose to store, there's nothing for a continuation to extend.
  private def appendContinuation(txn: ParsedLine, fields: Array[String]): ParsedLine =
    config.fieldMappings.get(BAIField.Text) match
      case None => txn
      case Some(textPropName) =>
        val extra = fields.drop(1).mkString(",")
        txn.copy(props = txn.props.updated(textPropName, txn.props.getOrElse(textPropName, "") + extra))

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
