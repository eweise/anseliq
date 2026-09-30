package onparity.domain.model

import com.augustnagro.magnum.*
import onparity.common.model.AppliedRules
import onparity.common.model.model.{ID, Money}
import onparity.domain.model.ReconciliationStatus.Final
import onparity.domain.model.helper.upickleJsonBDbCodec

import java.time.OffsetDateTime
import java.util.UUID

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
enum ReconciliationStatus derives DbCodec:
  // Reversed: links a movement to its Ledger.reverseMovement contra-entry (see there for why —
  // permanently excludes both from every "unreconciled movement" query, which all already filter
  // on reconciliation_id IS NULL). Never governed by a real ReconciliationSpec (a reversal pairs a
  // movement with itself, not two different movement specs), always balance = 0, and — unlike every
  // other status — must never be unmatched: see ManualReconciler.unmatch's guard.
  case InProgress, Final, Voided, Reversed

@Table(PostgresDbType, SqlNameMapper.CamelToSnakeCase)
case class Reconciliation(
  @Id id:               ID                     = createID(),
  // None only for a Reversed reconciliation — see ReconciliationStatus.Reversed's doc. No default:
  // every caller should have to consciously decide this, not fall into None by omission.
  specId:               Option[ID],
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
