package anseliq.domain.model

import anseliq.common.model.AppliedRules
import anseliq.common.model.model.{ID, Money}
import anseliq.domain.model.ReconciliationStatus.Final
import anseliq.domain.model.helper.upickleJsonBDbCodec
import com.augustnagro.magnum.*

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum ReconciliationStatus derives DbCodec:
  // InProgress: a OneToMany group whose amounts don't net to zero yet but may still be completed
  // by more of the "many" side arriving, so nothing is wrong with it yet — it's still being worked.
  // Mismatched: matched on key, but the amounts don't net to zero and it isn't waiting on anything:
  // a OneToOne pair (no third movement can ever join it), a OneToMany group past its spec's
  // agingLimit, or a manual match that doesn't balance. Always has an open AmountMismatch dispute.
  // Not terminal: a write-off, an unmatch, or (OneToMany only) a late movement closing the gap
  // takes it to Final/Voided.
  // Reversed: links a movement to its Ledger.reverseMovement contra-entry (see there for why —
  // permanently excludes both from every "unreconciled movement" query, which all already filter
  // on reconciliation_id IS NULL). Never governed by a real ReconciliationSpec (a reversal pairs a
  // movement with itself, not two different movement specs), always balance = 0, and — unlike every
  // other status — must never be unmatched: see ManualReconciler.unmatch's guard.
  case InProgress, Mismatched, Final, Voided, Reversed

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
  @Id id: ID = createID(),
  // None only for a Reversed reconciliation — see ReconciliationStatus.Reversed's doc. No default:
  // every caller should have to consciously decide this, not fall into None by omission.
  specId: Option[ID],
  // No default, same reasoning as specId: every caller states why the group was formed.
  appliedRules:         AppliedRules,
  description:          String                 = "",
  var status:           ReconciliationStatus   = Final,
  var balance:          Money                  = 0,
  var adjustmentReason: Option[String]         = None,
  var createdAt:        Option[OffsetDateTime] = None,
  var updatedAt:        Option[OffsetDateTime] = None
) derives DbCodec

object Reconciliation:
  given DbCodec[AppliedRules] = upickleJsonBDbCodec[AppliedRules]

  val Table = TableInfo[Reconciliation, Reconciliation, UUID]
