package onparity.common.model

import upickle.ReadWriter

// Lives in common (rather than backend-only or frontend-only) so the exact same rules run in both
// places: the frontend can show inline errors before the user ever hits Save, and the backend
// re-checks the same rules as a safety net against a bypassed/buggy frontend. Empty result means valid.
sealed trait ParserConfig derives ReadWriter:
  def validate: List[String] = Nil

// Shared by every ParserConfig's validate: a field/column stored under the same name as another
// would silently clobber it in the parsed props map (last one wins), which is always a mistake
// rather than something a user would do on purpose.
private def duplicateValues(values: Iterable[String]): List[String] =
  values
    .filter(_.nonEmpty)
    .groupBy(identity)
    .collect { case (v, occurrences) if occurrences.size > 1 => v }
    .toList
    .sorted

case class CSVParserConfig(
  hasHeader:         Boolean,
  columnDefinitions: Map[Int, String],
  amountField:       Option[Int]
) extends ParserConfig:
  override def validate: List[String] =
    val errors = List.newBuilder[String]
    if columnDefinitions.isEmpty then errors += "At least one column must be defined"
    if columnDefinitions.keys.exists(_ < 0) then errors += "Column numbers must not be negative"
    if columnDefinitions.values.exists(_.isBlank) then errors += "Every column must have a field name"
    val dups = duplicateValues(columnDefinitions.values)
    if dups.nonEmpty then errors += s"Field name(s) used more than once: ${dups.mkString(", ")}"
    if amountField.isEmpty then errors += "One column must be marked as the amount field"
    if amountField.exists(_ < 0) then errors += "Amount column number must not be negative"
    errors.result()

// BAI2 (Bank Administration Institute) cash management format: the set of possible fields is a
// fixed standard (unlike CSV, where columns are arbitrary), so configuration is "which of these
// known fields to store, and under what property name" rather than a user-defined column list.
// Amount isn't a selectable field here — its position on every transaction detail record is
// always known, so it's captured automatically instead of something the user opts into.
enum BAIField derives ReadWriter:
  case AccountNumber, TypeCode, FundsType, BankReferenceNumber, CustomerReferenceNumber, Text

case class BAIParserConfig(
  fieldMappings: Map[BAIField, String] = BAIParserConfig.defaultFieldMappings
) extends ParserConfig:
  override def validate: List[String] =
    val errors = List.newBuilder[String]
    if fieldMappings.values.exists(_.isBlank) then
      errors += "Every included BAI2 field must have a name to store it under"
    val dups = duplicateValues(fieldMappings.values)
    if dups.nonEmpty then errors += s"Field name(s) used more than once: ${dups.mkString(", ")}"
    errors.result()

object BAIParserConfig:
  val defaultFieldMappings: Map[BAIField, String] = Map(
    BAIField.AccountNumber           -> "account_number",
    BAIField.TypeCode                -> "type_code",
    BAIField.FundsType               -> "funds_type",
    BAIField.BankReferenceNumber     -> "bank_reference_number",
    BAIField.CustomerReferenceNumber -> "customer_reference_number",
    BAIField.Text                    -> "text"
  )

// JSON-lines source: one standalone JSON document per file row (not a single JSON array covering
// the whole file). Like CSV, the set of fields is arbitrary and user-defined, so fieldMappings is
// keyed by a JSONPath expression (e.g. "$.transaction.customerRef") rather than a fixed field set
// like BAI2 — the user identifies both which value to pull and what property name to store it
// under, the same "which + what name" shape as CSV's columnDefinitions. amountPath is the JSONPath
// to the amount, mirroring CSV's amountField — unlike BAI2, JSON has no fixed position to infer it
// from automatically.
case class JSONParserConfig(
  fieldMappings: Map[String, String] = Map.empty,
  amountPath:    Option[String]      = None
) extends ParserConfig:
  override def validate: List[String] =
    val errors = List.newBuilder[String]
    if fieldMappings.isEmpty then errors += "At least one field must be defined"
    if fieldMappings.keys.exists(_.isBlank) then errors += "Every field must have a JSON path"
    if fieldMappings.values.exists(_.isBlank) then errors += "Every field must have a stored name"
    val dups = duplicateValues(fieldMappings.values)
    if dups.nonEmpty then errors += s"Field name(s) used more than once: ${dups.mkString(", ")}"
    if amountPath.isEmpty then errors += "One field must be marked as the amount field"
    if amountPath.exists(_.isBlank) then errors += "Amount path must not be blank"
    errors.result()

// Positional source: every line has the same field layout at fixed character positions, as
// commonly described in mainframe/bank file specs (e.g. "positions 1-10: account number"). Fields
// are arbitrary and user-defined (like CSV columns, unlike BAI2's fixed set), so each one carries
// its own name, position range, and whether it's the amount — a per-field flag rather than a
// separate amountField index (CSV/JSON's approach), since there's no other natural single key to
// point at one of an unordered, user-editable set of position ranges.
case class FixedWidthField(
  name:          String,
  startPosition: Int,
  endPosition:   Int,
  isAmount:      Boolean = false
) derives ReadWriter

case class FixedWidthParserConfig(
  hasHeader: Boolean              = false,
  fields:    Seq[FixedWidthField] = Seq.empty
) extends ParserConfig:
  override def validate: List[String] =
    val errors = List.newBuilder[String]

    def label(f: FixedWidthField, idx: Int): String = if f.name.nonEmpty then s"'${f.name}'" else s"#${idx + 1}"

    if fields.size < 2 then errors += "At least two fields must be defined"
    val amountCount = fields.count(_.isAmount)
    if fields.nonEmpty && amountCount == 0 then errors += "One field must be marked as the amount field"
    if amountCount > 1 then errors += "Only one field may be marked as the amount field"
    if fields.exists(_.name.isBlank) then errors += "Every field must have a name"
    val dups = duplicateValues(fields.map(_.name))
    if dups.nonEmpty then errors += s"Field name(s) used more than once: ${dups.mkString(", ")}"
    if fields.exists(_.startPosition < 1) then errors += "Start position must be at least 1 (positions are 1-based)"

    val indexed = fields.zipWithIndex
    indexed.foreach { (f, idx) =>
      if f.endPosition < f.startPosition then
        errors += s"Field ${label(f, idx)}: end position must not be less than start position"
    }

    // Only compare ranges that are individually well-formed, so a single malformed field (already
    // reported above) doesn't also trigger a confusing cascade of spurious overlap errors.
    val wellFormed = indexed.filter((f, _) => f.endPosition >= f.startPosition)
    for
      (a, i) <- wellFormed
      (b, j) <- wellFormed
      if i < j
      if a.startPosition <= b.endPosition && b.startPosition <= a.endPosition
    do errors += s"Fields ${label(a, i)} and ${label(b, j)} have overlapping positions"

    errors.result()

case class DataSourceConfig(
  parserConfig:     ParserConfig,
  connectionConfig: ConnectionConfig = S3ConnectionConfig("", "") // fixme should default this?
) derives ReadWriter:
  def validate: List[String] = parserConfig.validate

sealed trait ConnectionConfig derives ReadWriter
case class S3ConnectionConfig(
  s3Bucket: String,
  filePath: String
) extends ConnectionConfig
