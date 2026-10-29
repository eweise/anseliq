package anseliq.view

import anseliq.common.model.{AccountPayload, AccountTypePayload, MovementSpecPayload}

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object ViewHelper:
  private val customFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def formatDateTime(dt: OffsetDateTime): String =
    dt.format(customFormatter)

  // The standard ledger setup: a Primary account settles OUT to Recon, and Recon settles OUT to
  // a Secondary account — so Recon receives from Primary and pays out to Secondary, netting to zero
  // when the amounts match. Returns (outAccountId, inAccountId) for a MovementSpec linking the two.
  def accountLinkFields(nonRecon: AccountPayload, recon: AccountPayload): (String, String) =
    if nonRecon.accountType == AccountTypePayload.Primary then (nonRecon.id.getOrElse(""), recon.id.getOrElse(""))
    else (recon.id.getOrElse(""), nonRecon.id.getOrElse(""))

  // A reconciliation's two sides, named by their account (e.g. "Journal") rather than in/out.
  // Money flows Primary → Recon → Secondary, so the Primary side's movement spec pays into the Recon
  // account (its out account is the Primary one) and the Secondary side's pays out of it (its in
  // account is the Secondary one). Until that spec or account is known, the side is named by its role.
  def primarySideName(movementSpecId: String, specs: Seq[MovementSpecPayload], accounts: Seq[AccountPayload]): String =
    sideName(specs.find(_.id.contains(movementSpecId)).map(_.outAccountId), accounts, "Primary")

  def secondarySideName(movementSpecId: String, specs: Seq[MovementSpecPayload], accounts: Seq[AccountPayload]): String =
    sideName(specs.find(_.id.contains(movementSpecId)).map(_.inAccountId), accounts, "Secondary")

  private def sideName(accountId: Option[String], accounts: Seq[AccountPayload], role: String): String =
    accountId.flatMap(id => accounts.find(_.id.contains(id))).map(_.name).getOrElse(role)
