package onparity.common.model

import upickle.ReadWriter

sealed trait ParserConfig derives ReadWriter
case class CSVParserConfig(
  hasHeader:         Boolean,
  columnDefinitions: Map[Int, String],
  amountField:       Option[Int]
) extends ParserConfig

// BAI2 (Bank Administration Institute) cash management format: the set of possible fields is a
// fixed standard (unlike CSV, where columns are arbitrary), so configuration is "which of these
// known fields to store, and under what property name" rather than a user-defined column list.
// Amount isn't a selectable field here — its position on every transaction detail record is
// always known, so it's captured automatically instead of something the user opts into.
enum BAIField derives ReadWriter:
  case AccountNumber, TypeCode, FundsType, BankReferenceNumber, CustomerReferenceNumber, Text

case class BAIParserConfig(
  fieldMappings: Map[BAIField, String] = BAIParserConfig.defaultFieldMappings
) extends ParserConfig

object BAIParserConfig:
  val defaultFieldMappings: Map[BAIField, String] = Map(
    BAIField.AccountNumber           -> "account_number",
    BAIField.TypeCode                -> "type_code",
    BAIField.FundsType               -> "funds_type",
    BAIField.BankReferenceNumber     -> "bank_reference_number",
    BAIField.CustomerReferenceNumber -> "customer_reference_number",
    BAIField.Text                    -> "text"
  )

case class DataSourceConfig(
  parserConfig:     ParserConfig,
  connectionConfig: ConnectionConfig = S3ConnectionConfig("", "") // fixme should default this?
) derives ReadWriter

sealed trait ConnectionConfig derives ReadWriter
case class S3ConnectionConfig(
  s3Bucket: String,
  filePath: String
) extends ConnectionConfig
