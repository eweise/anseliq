package onparity.domain.service

import onparity.common.model.model.Properties
import onparity.common.model.{BAIField, BAIParserConfig, CSVParserConfig, ParserConfig}
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
      case bai: BAIParserConfig => BAIParser(bai)

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
