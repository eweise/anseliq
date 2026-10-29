# Todo

## Server
* Replace the comma-split CSV parsing with a real CSV parser; quoted fields with commas break (`CsvParser.scala`)
* `submitAndProcessJob` may roll back the transaction that records the job's error (`JobManager.scala`)
* Add tracing (logging context exists via `LogContext`)
* Remove the default for `Movement.referenceId`
* Add a datetime modifier for rules (`Rules.scala`)
* Decide whether `connectionConfig` should default to S3 (`DataSourceConfig.scala`)

## Jobs
* `GenerateReconStats` has no `JobTypeInfo`, so those jobs never appear on the jobs page

## Testing
* Check whether a full two-stream reconciliation setup with input data exists
* Need repo testing of auditable attributes
