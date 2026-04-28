package perfectpair.common.model

import upickle.ReadWriter

sealed trait JobInfo derives ReadWriter

case class ReconciliationInfo(
  accountId:                   String,
  accountName:                 String,
  reconciliationsCreatedCount: Int,
  processingTimeSeconds:       Long
) extends JobInfo

enum IngestionDataSource derives ReadWriter:
  case FTP, S3, API, Manual

case class IngestionInfo(
  movementSpecId:          String,
  movementSpecName:        String = "",
  dataSource:              IngestionDataSource,
  filePath:                String = "",
  numberOfRecordsIngested: Int,
  processingTimeSeconds:   Long
) extends JobInfo
