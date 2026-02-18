package perfectpair.common.model

import upickle.ReadWriter

sealed trait ParserConfig derives ReadWriter
case class CSVParserConfig(
  hasHeader:         Boolean,
  columnDefinitions: Map[Int, String],
  amountField:       Option[Int]
) extends ParserConfig

//todo need full definition
case class DataSourceConfig(
  parserConfig: ParserConfig
) derives ReadWriter
