package anseliq.common.model

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
  numberOfRecordsIngested: Int,
  processingTimeSeconds:   Long
) extends JobInfo

case class FileInventoryInfo(
  filesQueued: Int
) extends JobInfo

case class ReconStatsInfo(
  ledgersSnapshotted:    Int,
  processingTimeSeconds: Long
) extends JobInfo

case class MatchSuggestionInfo(
  accountId:             String,
  movementsConsidered:   Int,
  suggestionsCreated:    Int,
  processingTimeSeconds: Long
) extends JobInfo
