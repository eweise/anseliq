# Todo

## Server
* Make the S3 endpoint configurable and stop hardcoding credentials (`S3FileSource.scala`)
* Use a pooled datasource (`DbDataSource.scala`; see the note there about `withUnreconciledCursor`)
* Replace the comma-split CSV parsing with a real CSV parser; quoted fields with commas break (`CsvParser.scala`)
* `submitAndProcessJob` may roll back the transaction that records the job's error (`JobManager.scala`)
* Add tracing (logging context exists via `LogContext`)
* Remove the default for `Movement.referenceId`
* Add a datetime modifier for rules (`Rules.scala`)
* Decide whether `connectionConfig` should default to S3 (`DataSourceConfig.scala`)

## Jobs
* `GenerateReconStats` has no `JobTypeInfo`, so those jobs never appear on the jobs page
* Clear `errorMessage` when a job completes, so runs that succeeded after a retry don't show the old error

## Testing
* Check whether a full two-stream reconciliation setup with input data exists (the large-file test may cover it)
* Need repo testing of auditable attributes
