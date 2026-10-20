package anseliq.domain.model

import anseliq.common.model.model.{ID, Money}
import anseliq.domain.model.helper.JSONBProps
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum MovementType derives DbCodec:
  // ManualAdjustment: a correction entry posted by Ledger.recordAdjustment, not ingested from a
  // file. Why it was posted is its adjustmentType (see AdjustmentType below), not implied by this.
  case Ingestion, ManualAdjustment, Reversal

// Why a ManualAdjustment movement was posted. MovementType says how a movement entered the ledger;
// this says what an adjustment is for, so a new kind of adjustment doesn't get mistaken for an
// existing one. WriteOff: posted by ManualReconciler.writeOff to close a reconciliation's
// difference.
@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum AdjustmentType derives DbCodec:
  case WriteOff

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Movement(
  @Id id:               ID         = createID(),
  referenceId:          ID         = createID(), // serves as the idempotency id //todo remove default
  outAccountId:         ID,
  outAccountBalance:    Money      = 0,
  inAccountId:          ID,
  inAccountBalance:     Money      = 0,
  amount:               Money,
  properties:           JSONBProps = JSONBProps(Map()),
  metaData:             JSONBProps = JSONBProps(Map()),
  movementSpecId:       ID,
  var reconciliationId: Option[ID] = None,
  // None only for a row that predates this field — no backfill has been run (see
  // feedback_no_backfill_until_migration_strategy memory); every new write sets it explicitly.
  // Classification only — NOT the mechanism that excludes a reversal from matching (that's still
  // Ledger.isReversal, based on the metaData reversal_of_movement_id tag, which is already correct
  // on every reversal ever created, past and future; switching that check to movementType would
  // silently stop excluding pre-existing reversal rows that have no movementType set).
  movementType: Option[MovementType] = None,
  // Set only on a ManualAdjustment: what it's for, and the note the person gave when posting it
  // (e.g. a write-off's "bank fee"), so the movement explains itself without its reconciliation.
  adjustmentType: Option[AdjustmentType] = None,
  adjustmentNote: Option[String]         = None,
  var createdAt:  Option[OffsetDateTime] = None,
  var updatedAt:  Option[OffsetDateTime] = None
) extends Audited derives DbCodec:

  def props = this.properties.props

  def signedAmountFor(accountID: ID): Money =
    if inAccountId == accountID then amount else -amount

object Movement:
  val Table = TableInfo[Movement, Movement, UUID]
