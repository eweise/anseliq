package onparity.common.model

import upickle.ReadWriter

sealed trait ParserConfig derives ReadWriter
case class CSVParserConfig(
  hasHeader:         Boolean,
  columnDefinitions: Map[Int, String],
  amountField:       Option[Int]
) extends ParserConfig

// BAI2 (Bank Administration Institute) cash management format: no per-instance configuration
// needed, unlike CSV — BAI2's field layout is a fixed standard, not something each source defines.
case object BAIParserConfig extends ParserConfig

case class DataSourceConfig(
  parserConfig:     ParserConfig,
  connectionConfig: ConnectionConfig = S3ConnectionConfig("", "") // fixme should default this?
) derives ReadWriter

sealed trait ConnectionConfig derives ReadWriter
case class S3ConnectionConfig(
  s3Bucket: String,
  filePath: String
) extends ConnectionConfig
