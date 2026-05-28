package perfectpair.common.model

import upickle.ReadWriter

sealed trait ParserConfig derives ReadWriter
case class CSVParserConfig(
  hasHeader:         Boolean,
  columnDefinitions: Map[Int, String],
  amountField:       Option[Int]
) extends ParserConfig

case class DataSourceConfig(
  parserConfig:     ParserConfig,
  connectionConfig: ConnectionConfig
) derives ReadWriter

sealed trait ConnectionConfig derives ReadWriter
case class S3ConnectionConfig(
  s3Bucket: String,
  filePath: String
) extends ConnectionConfig
